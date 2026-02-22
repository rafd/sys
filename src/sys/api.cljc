(ns sys.api
  (:refer-clojure :exclude [get])
  (:require
   [sys.internals :as i]))

(defonce systems (atom {}))

;; in start!, stop!, set! we apply the stateful function and assoc (instead of using it as an update)
;; because nested systems might mutate the system object

(defn start! [system-id]
  (let [result (i/start (clojure.core/get @systems system-id))]
    (swap! systems assoc system-id result))
  nil)

(defn stop! [system-id]
  (let [result (i/stop (clojure.core/get @systems system-id))]
    (swap! systems assoc system-id result))
  nil)

(defn context
  [system-id]
  (get-in @systems [system-id ::i/context]))

(defn get [system-id k]
  (clojure.core/get (context system-id) k))

(defn set!
  [system-id components]
  (let [system   (clojure.core/get @systems system-id)
        started? (seq (::i/active-components system))
        _        (when started?
                   (i/stop system))
        new-system (i/init system-id components)
        result   (if started?
                   (i/start new-system)
                   new-system)]
    (swap! systems assoc system-id result))
  nil)

