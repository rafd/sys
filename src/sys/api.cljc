(ns sys.api
  (:refer-clojure :exclude [get])
  (:require
   [sys.topo :as topo]
   [malli.core :as m]
   [clojure.set :as set]))

(def ComponentDefinition
  [:map
   [:sys.component/id keyword?]
   [:sys.component/expects {:optional true} [:set keyword?]]
   [:sys.component/provides {:optional true} [:set keyword?]]
   [:sys.component/start {:optional true} fn?]
   [:sys.component/stop {:optional true} fn?]])

(def SystemObject
  [:map
   [::init-components [:set ComponentDefinition]]
   [::active-components [:vector ComponentDefinition]]
   [::sorted-components [:vector ComponentDefinition]]
   [::exception any?]])

(defn all-provides-unique?
  [components]
  (->> (mapcat :sys.component/provides components)
       (apply distinct?)))

(defn init
  [components]
  {:pre [(m/validate [:seqable ComponentDefinition] components)
         (all-provides-unique? components)]}
  {::init-components (set components)
   ::active-components []
   ::sorted-components (topo/topo-sort (set components)
                                       {:->expects :sys.component/expects
                                        :->provides :sys.component/provides})
   ::exception nil})

(defn init!
  [components]
  (atom (init components)))

(defn start
  [{::keys [active-components sorted-components] :as system}]
  (println "Starting system...")
  (let [system (assoc system ::exception nil)
        active? (set active-components)]
    (->> sorted-components
         (reduce (fn [system {:sys.component/keys [id start expects provides]
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
                         (let [result (start (select-keys system expects))]
                           (when-let [missing-keys (and (seq provides)
                                                        (seq
                                                         (set/difference
                                                          (set provides)
                                                          (set (keys result)))))]
                             (throw (ex-info (str "Component with id "
                                                  id
                                                  " did not provide "
                                                  missing-keys
                                                  " as declared.")
                                             {:id id
                                              :missing-keys missing-keys})))
                           (-> system
                               ;; if provides is empty or nil select-keys returns
                               ;; an empty map which is fine for our purpuses
                               (merge (select-keys result provides))
                               (update ::active-components conj component)))
                         (catch #?(:clj Exception :cljs js/Error) e
                           (println "Error " id " (Error:" (.getMessage e) ")")
                           (reduced (assoc system
                                           ::exception e)))))))
                 system))))

(defn start! [system-atom]
  (swap! system-atom start)
  system-atom)

(defn stop [{::keys [active-components] :as system}]
  (println "Stopping system...")
  (let [system (assoc system ::exception nil)]
    (->> active-components
         reverse
         (reduce (fn [system {:sys.component/keys [id stop provides]}]
                   (cond
                     (nil? stop)
                     (do
                       (println "Skipping" id "(no stop function)")
                       system)

                     :else
                     (do
                       (println "Stopping" id)
                       (try
                         (stop (select-keys system provides))
                         (-> system
                             (update ::active-components pop))
                         (catch #?(:clj Exception :cljs js/Error) e
                           (println "Error " id "(" (.getMessage e) ")")
                           (reduced (assoc system
                                           ::exception e)))))))
                 system))))

(defn stop! [system-atom]
  (swap! system-atom stop)
  system-atom)

(defn get [system-atom k]
  (clojure.core/get (deref system-atom) k))
