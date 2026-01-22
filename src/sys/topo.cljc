(ns sys.topo
  (:require
   [clojure.set :as set]))

(defn flip [nodes ->x]
  (->> (for [node nodes
             p (->x node)]
         {p #{node}})
       (apply merge-with into)))

#_(= (flip
      [:foo :bar :baz]
      {:foo #{:a :b}
       :bar #{:a :c}})
     {:a #{:foo :bar}
      :b #{:foo}
      :c #{:bar}})

(defn all-expects-provided?
  [nodes {:keys [->expects ->provides]}]
  (set/subset?
   (set (mapcat ->expects nodes))
   (set (mapcat ->provides nodes))))

(defn topo-sort
  [nodes {:keys [->expects ->provides] :as opts}]
  {:pre [(all-expects-provided? nodes opts)]}
  (loop [ordered []
         remaining (set nodes)
         provided #{}]
    (if (empty? remaining)
      ordered
      (if-let [next-node (->> remaining
                              ;; first node that has all it's dependencies satisfied
                              (some (fn [node]
                                      (when (empty? (set/difference (->expects node)
                                                                    provided))
                                        node))))]
        (recur (conj ordered next-node)
               (disj remaining next-node)
               (into provided (->provides next-node)))
        (throw (ex-info "Cycle Detected" {}))))))

#_(topo-sort
   [:C :A :D :B]
   {:->expects {:C #{:B}
                :B #{:A}
                :A #{}
                :D #{:C}}
    :->provides (fn [x] (set [x]))})

#_(topo-sort
   [:A :B]
   {:->expects {:B #{:C}
                :A #{}}
    :->provides (fn [x] (set [x]))})

#_(topo-sort
   [:A :B]
   {:->expects {:B #{:A}
                :A #{:B}}
    :->provides (fn [x] (set [x]))})
