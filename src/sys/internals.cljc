(ns sys.internals
  (:require
   [sys.topo :as topo]
   [malli.core :as m]
   [malli.experimental.lite :as ml]
   [malli.error :as me]))

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
  [components]
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
    {::init-components (set components)
     ::active-components []
     ::sorted-components (topo/topo-sort (set components)
                                         {:->expects (fn [c] (->keys (:sys.component/expects-schema c)))
                                          :->provides (fn [c] (->keys (:sys.component/provides-schema c)))})
     ::context {}
     ::exception nil}))

(defn start
  [{::keys [active-components sorted-components] :as system}]
  (println "Starting system...")
  (let [system (assoc system ::exception nil)
        active? (set active-components)]
    (->> sorted-components
         (reduce (fn [system {:sys.component/keys [id start expects-schema provides-schema]
                              :as component}]
                   (cond
                     (active? component)
                     (do (println "Skipping" id " (already active)")
                         system)

                     (nil? start)
                     (do
                       (println "Skipping" id " (no start function)")
                       system)

                     :else
                     (do
                       (println "Starting" id)
                       (try
                         (let [result (start (select-keys (::context system) (->keys expects-schema)))]
                           (when-let [errors (m/explain provides-schema result)]
                             (throw (ex-info (str "Component with id "
                                                  id
                                                  " did not provide values as declared: "
                                                  (me/humanize errors))
                                             {:id id
                                              :errors errors})))
                           (-> system
                               ;; if provides-schema is empty map, select-keys returns
                               ;; an empty map which is fine for our purpuses
                               (update ::context merge (select-keys result (->keys provides-schema)))
                               (update ::active-components conj component)))
                         (catch #?(:clj Exception :cljs js/Error) e
                           (println "Error " id " (Error:" (.getMessage e) ")")
                           (reduced (assoc system
                                           ::exception e)))))))
                 system))))

(defn stop [{::keys [active-components] :as system}]
  (println "Stopping system...")
  (let [system (assoc system ::exception nil)]
    (->> active-components
         reverse
         (reduce (fn [system {:sys.component/keys [id stop provides-schema]}]
                   (try
                     (if (nil? stop)
                       (println "Skipping" id "(no stop function)")
                       (do
                         (println "Stopping" id)
                         (stop (select-keys (::context system) (->keys provides-schema)))))
                     (-> system
                         (update ::active-components pop)
                         (update ::context (partial apply dissoc) (->keys provides-schema)))
                     (catch #?(:clj Exception :cljs js/Error) e
                       (println "Error " id "(" (.getMessage e) ")")
                       (reduced (assoc system
                                       ::exception e)))))
                 system))))

