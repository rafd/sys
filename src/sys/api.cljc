(ns sys.api
  (:refer-clojure :exclude [get])
  (:require
   [sys.internals :as i]))

(defonce *systems (atom {}))

(defn start! [system-id]
  (i/start! *systems system-id)
  nil)

(defn stop! [system-id]
  (i/stop! *systems system-id)
  nil)

(defn context
  [system-id]
  (get-in @*systems [system-id ::i/context]))

(defn get [system-id k]
  (clojure.core/get (context system-id) k))

(defn set!
  [system-id components]
  (let [started? (seq (::i/active-components (clojure.core/get @*systems system-id)))]
    (when started?
      (i/stop! *systems system-id))
    (swap! *systems assoc system-id (i/init system-id components))
    (when started?
      (i/start! *systems system-id)))
  nil)

