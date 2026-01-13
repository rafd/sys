# sys

[![Clojars Project](https://img.shields.io/clojars/v/com.github.rafd/sys.svg)](https://clojars.org/com.github.rafd/sys)

A boring dependency injection system for Clojure(Script) apps.

- Ordered startup and shutdown of components based on declared dependencies.
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
                             ;; use your favorite config library
                             {:db-url "jdbc://..."
                              :http-port 8080})})

(def db-component
  {:sys.component/id       :db
   :sys.component/expects  #{:db-url}
   :sys.component/provides #{:db-conn}
   :sys.component/start    (fn [{:keys [db-url]}]
                              {:db-conn (db/conn db-url)})
   :sys.component/stop     (fn [{:keys [db-conn]}]
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

(def prod-system
  (sys/init!
    [db-component
     config-component
     http-server-component]))

(defn -main
  [& _]
  (sys/start! prod-system)
  (.addShutdownHook
   (Runtime/getRuntime)
   (Thread. #(sys/stop! prod-system)))
  @(promise))

(comment
 (sys/start! prod-system)
 
 (sys/context @prod-system)
 ;; {:db-conn ... :db-url ... :http-port ...}
 
 (sys/get @prod-system :http-port)
 ;; 8080
 
 (sys/stop! prod-system))
```

Want a prod and dev system? Create two lists of components. Or, one list that filters out some things from the other. No helpers for this at the moment, it's up to you.

See [test/sys/test/e2e.clj](test/sys/test/e2e.clj) for a more complete example.

See [test/sys/test/api.clj](test/sys/test/api.clj) for a test namespaces that walks through all the features.

