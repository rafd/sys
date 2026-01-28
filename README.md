# sys

[![Clojars Project](https://img.shields.io/clojars/v/com.github.rafd/sys.svg)](https://clojars.org/com.github.rafd/sys)

A boring dependency injection system for Clojure(Script) apps.

- Ordered startup and shutdown of components based on declared dependencies.
- Validates expects and provides for each component (optionally also checking types using malli schemas).
- Resumes startup/shutdown from failure points.

```clojure
(ns my-app.core
  (:require
    [sys.api :as sys]))

(def config-component
  {:sys.component/id       :config
   :sys.component/expects  #{}
   :sys.component/provides #{:db-url :http-port}
   :sys.component/start    (fn [_]
                             ;; (use your favorite config library here)
                             {:db-url "jdbc://..."
                              :http-port 8080})})

(def db-component
  {:sys.component/id       ;; id key is mandatory (all others optional)
                           :db
   :sys.component/expects  ;; can use malli-lite schemas:
                           {:db-url [:re "^jdbc://.*"]}
   :sys.component/provides ;; or just sets:
                           #{:db-conn}
   :sys.component/start    ;; start receives what it :expects
                           (fn [{:keys [db-url]}]
                              ;; component must return what it :provides
                              {:db-conn (db/conn db-url)})
   :sys.component/stop     ;; stop receives what it :provides
                           (fn [{:keys [db-conn]}]
                              (db/close db-conn))})

(def http-server-component
  #:sys.component
  {:id       :component-2
   :expects  #{:http-port}
   :provides #{:server}
   :start    (fn [{:keys [http-port]}]
               {:server (http/start http-port)})
   :stop     (fn [{:keys [server]}]
               (http/stop server))})


;; when sys/set! is called the first time for a given ::key
;;    that system is just "defined"; (sys/start! ::key) needs to be called to start it

;; when sys/set! is called subsequently, and the relevant system is running
;;   the system will be stopped and restarted
;;   this makes it easy to update/add/remove components during REPL development
;;   (at the cost of always restarting when re-running sys/set!)

;; to have your system *automatically* redefined and restarted when a component is re-defined
;;   (especially if that component is defined in another namespace than the system)
;;   you can use tools.namespace or clj-reload

(sys/set! ::prod
 [db-component
  config-component
  http-server-component])

(defn -main
  [& _]
  (sys/start! ::prod)
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. #(sys/stop! ::prod)))
  @(promise))

(comment
 (sys/start! ::prod)

 (sys/context ::prod)
 ;; {:db-conn ... :db-url ... :http-port ...}

 (sys/get ::prod :http-port)
 ;; 8080

 (sys/stop! ::prod)

 (sys/context ::prod)
 ;; {}
 ;; empty, since all components were stopped
 )
```

Want a prod and dev system? Create two lists of components. Or, one list that filters out some things from the other. No helpers for this at the moment, it's up to you.

See [test/sys/test/e2e.clj](test/sys/test/e2e.clj) for a more complete example.

See [test/sys/test/api.clj](test/sys/test/api.clj) for a test namespaces that walks through all the features.

