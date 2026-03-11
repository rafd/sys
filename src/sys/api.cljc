(ns sys.api
  (:refer-clojure :exclude [get])
  (:require
   [sys.internals :as i]))

(defonce *systems (atom {}))

(defn start! [system-id]
  (i/start! (clojure.core/get @*systems system-id))
  nil)

(defn stop! [system-id]
  (i/stop! (clojure.core/get @*systems system-id))
  nil)

(defn context
  [system-id]
  (::i/context @(clojure.core/get @*systems system-id)))

(defn get [system-id k]
  (clojure.core/get (context system-id) k))

(defn set!
  [system-id components]
  (let [*system (clojure.core/get @*systems system-id)
        started? (and *system (seq (::i/active-components @*system)))]
    (when started?
      (i/stop! *system))
    (swap! *systems assoc system-id (atom (i/init system-id components)))
    (when started?
      (i/start! (clojure.core/get @*systems system-id))))
  nil)
