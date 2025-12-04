(ns sys.test.e2e
  "Example with real http server and database using next.jdbc, hikari-cp and http-kit.

   Needs to be run with test-e2e profile (from project.clj)"
  (:require
   [next.jdbc :as jdbc]
   [hikari-cp.core :as hikari]
   [org.httpkit.server :as http]
   [sys.api :as sys]))

(defn config []
  {:http-port 1234
   :db-url "jdbc:h2:./target/db.h2db"})

(def config-component
  {:sys.component/id       :config
   :sys.component/expects  #{}
   :sys.component/provides #{:http-port :db-url}
   :sys.component/start    (fn [_]
                             (config))})

(def http-server-component
  {:sys.component/id       :http-server
   :sys.component/expects  #{:http-port :db-conn}
   :sys.component/provides #{:http-server}
   :sys.component/start    (fn [{:keys [http-port db-conn]}]
                             {:http-server
                              (http/run-server
                               (fn [_req]
                                 {:status 200
                                  :headers {"Content-Type" "text/plain"}
                                  :body (pr-str (jdbc/execute! db-conn ["SELECT * FROM contact"]))})
                               {:port http-port})})
   :sys.component/stop     (fn [{:keys [http-server]}]
                             (http-server :timeout 500))})

(def database-component
  {:sys.component/id       :database
   :sys.component/expects  #{:db-url}
   :sys.component/provides #{:db-conn}
   :sys.component/start    (fn [{:keys [db-url]}]
                             {:db-conn (hikari/make-datasource {:jdbc-url db-url})})
   :sys.component/stop     (fn [{:keys [db-conn]}]
                             (hikari/close-datasource db-conn))})

(def migrations-component
  {:sys.component/id       :create-tables
   :sys.component/expects  #{:db-conn}
   :sys.component/provides #{:migrations?}
   :sys.component/start    (fn [{:keys [db-conn]}]
                             (jdbc/execute! db-conn ["CREATE TABLE IF NOT EXISTS contact (id INT PRIMARY KEY, name VARCHAR(255))"])
                             {:migrations? true})})

(def seed-component
  {:sys.component/id       :seed
   :sys.component/expects  #{:db-conn :migrations?}
   :sys.component/provides #{}
   :sys.component/start    (fn [{:keys [db-conn]}]
                             (jdbc/execute! db-conn ["INSERT INTO contact (id, name) VALUES (1, 'Alice'), (2, 'Bob')"]))})

;; stateful api

(defonce prod-system
  (sys/init!
   #{database-component
     migrations-component
     config-component
     http-server-component}))

(defonce dev-system
  (sys/init!
   #{migrations-component
     seed-component
     database-component
     config-component
     http-server-component}))

#_(sys/start! prod-system)
#_(sys/stop! prod-system)

;; can get a value returned from a component
#_(sys/get prod-system :db-conn)

;; "unofficial" api to inspect the system
#_(keys @prod-system)
#_(map :sys.component/id (sys/get prod-system :sys.api/active-components))

;; pure api
;; (maintain the state in an atom yourself)

(def prod-components
  #{database-component
    config-component
    http-server-component})

(defonce my-prod-system (atom nil))

#_(reset! my-prod-system (sys/init prod-components))
#_(swap! my-prod-system sys/start)
#_(swap! my-prod-system sys/stop)

