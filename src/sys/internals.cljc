(ns sys.internals
  (:require
   [clojure.set :as set]
   [malli.core :as m]
   [malli.error :as me]
   [malli.experimental.lite :as ml]
   [taoensso.trove :as trove]
   [sys.topo :as topo]))

(def MalliSchema
  ;; no schema for malli schemas yet
  ;; https://github.com/metosin/malli/issues/872
  :any)

(def InputParamSpec
  [:or
   [:set :keyword]
   [:map-of :keyword :any]])

(def ComponentDefinition
  [:map
   [:sys.component/id :keyword]
   [:sys.component/expects {:optional true} InputParamSpec]
   [:sys.component/provides {:optional true} InputParamSpec]
   [:sys.component/expects-schema {:optional true} MalliSchema]
   [:sys.component/provides-schema {:optional true} MalliSchema]
   [:sys.component/start {:optional true} fn?]
   [:sys.component/stop {:optional true} fn?]])

(def SystemObject
  [:map
   [::id :keyword]
   [::init-components [:set ComponentDefinition]]
   [::active-components [:vector ComponentDefinition]]
   [::sorted-components [:vector ComponentDefinition]]
   [::context :map]
   [::exception any?]])

(def Systems
  [:map-of :any SystemObject])

(defn ->schema
  "Param specs may be sets or malli-lite notation. Normalize to malli schema."
  [params-spec]
  (cond
    ;; if no params-spec defined, we accept anything
    (nil? params-spec) (m/schema :any)
    (set? params-spec) (ml/schema
                        (zipmap params-spec
                                (repeat :any)))
    (map? params-spec) (ml/schema params-spec)
    :else (m/schema params-spec)))

(defn ->keys
  [schema]
  (set (map first (m/children schema))))

(defn duplicate-key-provides
  "Returns map of keys that are provided by more than one component, pointing to a set of the relevant components"
  [components]
  (->> (topo/flip components
                  (fn [c] (->keys (:sys.component/provides-schema c))))
       (filter (fn [[_ providing-components]]
                 (> (count providing-components) 1)))
       (into {})))

(defn init
  [system-id components]
  {:pre [(m/validate [:seqable ComponentDefinition] components)]}
  (let [components (->> components
                        (map (fn [component]
                               (-> component
                                   (assoc :sys.component/expects-schema (->schema (:sys.component/expects component)))
                                   (assoc :sys.component/provides-schema (->schema (:sys.component/provides component)))))))]
    (when-let [duplicates (seq (duplicate-key-provides components))]
      (throw (ex-info (str "Multiple components provide the same key: "
                           (update-vals duplicates (fn [components]
                                                     (map :sys.component/id components))))
                      {:duplicates duplicates})))
    (let [all-expected (set (mapcat (fn [c] (->keys (:sys.component/expects-schema c))) components))
          all-provided (set (mapcat (fn [c] (->keys (:sys.component/provides-schema c))) components))
          missing      (set/difference all-expected all-provided)]
      (when (seq missing)
        (throw (ex-info (str "Expected keys are not provided: " missing)
                        {:missing missing}))))
    {::id system-id
     ::init-components (set components)
     ::active-components []
     ::sorted-components (topo/topo-sort (set components)
                                         {:->expects (fn [c] (->keys (:sys.component/expects-schema c)))
                                          :->provides (fn [c] (->keys (:sys.component/provides-schema c)))})
     ::context {}
     ::exception nil}))

(defn start!
  [*system]
  (let [{::keys [id active-components sorted-components]} @*system]
    (trove/log! {:level :info
                 :id ::starting-system
                 :data {:system-id id}
                 :msg (str "Starting system " id " ...")})
    (swap! *system assoc ::exception nil)
    (let [active? (set active-components)]
      (doseq [{:sys.component/keys [id start expects-schema provides-schema]
               :as component} sorted-components
              :while (nil? (::exception @*system))]
        (cond
          (active? component)
          (trove/log! {:level :info
                       :id ::skipping-component
                       :data {:component-id id
                              :reason :already-active}
                       :msg (str "Skipping " id " (already active)")})

          (nil? start)
          (trove/log! {:level :info
                       :id ::skipping-component
                       :data {:component-id id
                              :reason :no-start-fn}
                       :msg (str "Skipping " id " (no start function)")})

          :else
          (do
            (trove/log! {:level :info
                         :id ::starting-component
                         :data {:component-id id}
                         :msg (str "Starting " id)})
            (try
              (let [result (start (select-keys (::context @*system) (->keys expects-schema)))]
                (when-let [errors (m/explain provides-schema result)]
                  (throw (ex-info (str "Component with id "
                                       id
                                       " did not provide values as declared: "
                                       (me/humanize errors))
                                  {:id id
                                   :errors errors})))
                (swap! *system
                       (fn [system]
                         (-> system
                             ;; if provides-schema is empty map, select-keys returns
                             ;; an empty map which is fine for our purpuses
                             (update ::context merge (select-keys result (->keys provides-schema)))
                             (update ::active-components conj component)))))
              (catch #?(:clj Exception :cljs js/Error) e
                (trove/log! {:level :error
                             :id ::component-error
                             :data {:component-id id}
                             :error e
                             :msg (str "Error " id " (" (.getMessage e) ")")})
                (swap! *system assoc ::exception e)))))))))

(defn stop!
  [*system]
  (trove/log! {:level :info
               :id ::stopping-system
               :msg "Stopping system..."})
  (swap! *system assoc ::exception nil)
  (let [active-components (::active-components @*system)]
    (doseq [{:sys.component/keys [id stop provides-schema]} (reverse active-components)
            :while (nil? (::exception @*system))]
      (try
        (if (nil? stop)
          (trove/log! {:level :info
                       :id ::skipping-component
                       :data {:component-id id
                              :reason :no-stop-fn}
                       :msg (str "Skipping " id " (no stop function)")})
          (do
            (trove/log! {:level :info
                         :id ::stopping-component
                         :data {:component-id id}
                         :msg (str "Stopping " id)})
            (stop (select-keys (::context @*system) (->keys provides-schema)))))
        (swap! *system
               (fn [system]
                 (-> system
                     (update ::active-components pop)
                     (update ::context (partial apply dissoc) (->keys provides-schema)))))
        (catch #?(:clj Exception :cljs js/Error) e
          (trove/log! {:level :error
                       :id ::component-error
                       :data {:component-id id}
                       :error e
                       :msg (str "Error " id " (" (.getMessage e) ")")})
          (swap! *system assoc ::exception e))))))

