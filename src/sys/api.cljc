(ns sys.api
  (:refer-clojure :exclude [get])
  (:require
   [sys.internals :as i]))

(defonce systems (atom {}))

(defn start! [system-id]
  (swap! systems update system-id i/start)
  nil)

(defn stop! [system-id]
  (swap! systems update system-id i/stop)
  nil)

(defn context
  [system-id]
  (get-in @systems [system-id ::i/context]))

(defn get [system-id k]
  (clojure.core/get (context system-id) k))

(defn set!
  [system-id components]
  (swap! systems update system-id (fn [system]
                                    (let [started? (seq (::i/active-components system))]
                                      (when started?
                                        (i/stop system))
                                      (let [new-system (i/init components)]
                                        (when started?
                                          (i/start new-system))
                                        new-system))))
  nil)

