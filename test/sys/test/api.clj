(ns sys.test.api
  (:require
   [clojure.test :refer [deftest testing is] :as t]
   [malli.core :as m]
   [malli.error :as me]
   [sys.api :as sys]
   [sys.internals :as i]))

(defmethod t/assert-expr 'valid? [msg form]
  ;; form is (valid? schema value)
  (let [[_ schema value] form]
    `(let [schema# ~schema
           value# ~value
           explain# (m/explain schema# value#)
           ok?# (nil? (:errors explain#))]
       (t/do-report
        {:type (if ok?# :pass :fail)
         :message ~msg
         :expected schema#
         :actual (if ok?# value# (me/humanize explain#))})
       ok?#)))

(defn valid? [& _]) ;; stubbing out above to satisfy clj-kondo

(defn clear!
  []
  (println "CLEAR!")
  (doseq [system (keys @sys/*systems)]
    (sys/stop! system))
  (reset! sys/*systems {}))

(deftest e2e
  (testing "set!"
    (testing
     "(internal) results in valid systems"
      (clear!)
      (sys/set! ::test #{#:sys.component
                          {:id       :component-1
                           :expects  #{}
                           :provides #{:a}
                           :start    (fn [_]
                                       {:a 1})}

                         #:sys.component
                          {:id       :component-2
                           :expects  {:a :int}
                           :provides {:b :int}
                           :start    (fn [{:keys [a]}]
                                       {:b (+ a 1)})}})
      (is (valid? i/Systems @sys/*systems)))

    (testing "throws exception when component definitions are invalid"
      (is (thrown-with-msg?
           java.lang.AssertionError
           #"m/validate"
           (sys/set! ::test #{{}}))))

    (testing "throws exception when dependencies are not met"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"not provided: #\{:b\}"
           (sys/set! ::test #{{:sys.component/id       :component-1
                               :sys.component/provides #{:a}}
                              {:sys.component/id       :component-2
                               :sys.component/expects  #{:b}}}))))

    (testing "throws exception when there are circular dependencies"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cycle Detected"
           (sys/set! ::test #{{:sys.component/id       :component-1
                               :sys.component/expects  #{:b}
                               :sys.component/provides #{:a}}
                              {:sys.component/id       :component-2
                               :sys.component/expects  #{:a}
                               :sys.component/provides #{:b}}}))))

    (testing "throws exception when multiple components provide same key"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Multiple components provide the same key: \{:a .*\}"
           (sys/set! ::test #{{:sys.component/id       :component-1
                               :sys.component/provides #{:a}}
                              {:sys.component/id       :component-2
                               :sys.component/provides {:a :int}}})))))

  (testing "start!"
    (let [starts (atom [])
          start-args (atom {})
          get-contexts (atom {})]
      (clear!)
      (sys/set! ::test #{{:sys.component/id       :component-1
                          :sys.component/expects  #{}
                          :sys.component/provides #{:a}
                          :sys.component/start    (fn [args]
                                                    (swap! starts conj :component-1)
                                                    (swap! start-args assoc :component-1 args)
                                                    (swap! get-contexts assoc :component-1 (sys/context ::test))
                                                    {:a 1})}
                         {:sys.component/id       :component-2
                          :sys.component/expects  #{:a}
                          :sys.component/provides #{:b}
                          :sys.component/start    (fn [{:keys [a] :as args}]
                                                    (swap! starts conj :component-2)
                                                    (swap! start-args assoc :component-2 args)
                                                    (swap! get-contexts assoc :component-2 (sys/context ::test))
                                                    {:b (+ a 1)
                                                     ;; return an extra key that is not provided
                                                     ;; (and thus not added to context)
                                                     :x 1})}
                         {:sys.component/id       :component-3
                          :sys.component/expects  #{:b}
                          :sys.component/start    (fn [{:keys [_b] :as args}]
                                                    (swap! starts conj :component-3)
                                                    (swap! start-args assoc :component-3 args)
                                                    (swap! get-contexts assoc :component-3 (sys/context ::test))
                                                    ;; component that provides nothing has the
                                                    ;; output of its start function ignored
                                                    [nil])}})
      (sys/start! ::test)

      (testing "calls start on all components, in dependency order"
        (is (= [:component-1 :component-2 :component-3] @starts)))

      (testing "(internal) systems remains valid"
        (is (valid? i/Systems @sys/*systems)))

      (testing "each component receives the values it expects (and only the ones it expects)"
        (is (= {:component-1 {}
                :component-2 {:a 1}
                :component-3 {:b 2}}
               @start-args)))

      (testing "context is updated after each start"
        (is (= {:component-1 {}
                :component-2 {:a 1}
                :component-3 {:a 1 :b 2}}
               @get-contexts)))

      (testing "resulting system has all provided keys (and only provided keys)"
        (is (= 1 (sys/get ::test :a)))
        (is (= 2 (sys/get ::test :b)))
        (is (= {:a 1 :b 2} (sys/context ::test))))

      (testing "repeating start on started system does not call start functions again"
        (sys/start! ::test)
        (is (= [:component-1 :component-2 :component-3] @starts))

        (testing "(internal) systems remains valid"
          (is (valid? i/Systems @sys/*systems)))))

    (testing "when a component does not provide what it declared (set), results in system with exception"
      (clear!)
      (sys/set! ::test #{{:sys.component/id       :component-1
                          :sys.component/provides #{:a}
                          :sys.component/start    (fn [_]
                                                    {:b 1})}})
      (sys/start! ::test)

      (is (= (-> sys/*systems
                 deref
                 ::test
                 ::i/exception
                 ex-message)
             "Component with id :component-1 did not provide values as declared: {:a [\"missing required key\"]}"))

      (testing "(internal) systems remains valid"
        (is (valid? i/Systems @sys/*systems))))

    (testing "when a component does not provide what it declared (malli spec), results in system with exception"
      (clear!)
      (sys/set! ::test #{{:sys.component/id       :component-1
                          :sys.component/provides {:a [:vector :int]}
                          :sys.component/start    (fn [_]
                                                    {:a 1})}})
      (sys/start! ::test)

      (is (= (-> sys/*systems
                 deref
                 ::test
                 ::i/exception
                 ex-message)
             "Component with id :component-1 did not provide values as declared: {:a [\"invalid type\"]}"))

      (testing "(internal) systems remains valid"
        (is (valid? i/Systems @sys/*systems))))

    (testing "when a component fails to start, does not start remaining components"
      (let [starts (atom [])
            stops (atom [])
            component-1 {:sys.component/id       :component-1
                         :sys.component/expects  #{}
                         :sys.component/provides #{:a}
                         :sys.component/start    (fn [_]
                                                   (swap! starts conj :component-1)
                                                   {:a 1})
                         :sys.component/stop (fn [_]
                                               (swap! stops conj :component-1))}
            component-2-broken {:sys.component/id       :component-2
                                :sys.component/expects  #{:a}
                                :sys.component/provides #{:b}
                                :sys.component/start    (fn [{:keys [a]}]
                                                          (swap! starts conj :component-2)
                                                          (throw (ex-info "component failed to start" {}))
                                                          {:b (+ a 1)})
                                :sys.component/stop (fn [_]
                                                      (swap! stops conj :component-2))}
            component-2-fixed {:sys.component/id       :component-2
                               :sys.component/expects  #{:a}
                               :sys.component/provides #{:b}
                               :sys.component/start    (fn [{:keys [a]}]
                                                         (swap! starts conj :component-2)
                                                         {:b (+ a 1)})
                               :sys.component/stop (fn [_]
                                                     (swap! stops conj :component-2))}
            component-3 {:sys.component/id       :component-3
                         :sys.component/expects  #{:b}
                         :sys.component/provides #{:c}
                         :sys.component/start    (fn [{:keys [b]}]
                                                   (swap! starts conj :component-3)
                                                   {:c (+ b 1)})
                         :sys.component/stop (fn [_]
                                               (swap! stops conj :component-3))}]
        (clear!)
        (sys/set! ::test [component-1 component-2-broken component-3])
        (sys/start! ::test)

        (is (= [:component-1 :component-2] @starts))

        (testing "(internal) systems remains valid"
          (is (valid? i/Systems @sys/*systems)))

        (testing "resulting system has all defined keys"
          (is (= 1 (sys/get ::test :a)))
          (is (= {:a 1} (sys/context ::test))))

        (testing "calling set! to replace a broken system..."
          (sys/set! ::test [component-1 component-2-fixed component-3])

          (testing "stops components"
            (is (= [:component-1] @stops)))

          (testing "starts again"
            (is (= [:component-1 :component-2 ;; before fixing
                    :component-1 :component-2 :component-3] @starts)))

          (testing "resulting system has all defined keys"
            (is (= 1 (sys/get ::test :a)))
            (is (= {:a 1 :b 2 :c 3} (sys/context ::test))))

          (testing "(internal) systems remains valid"
            (is (valid? i/Systems @sys/*systems)))))))

  (testing "stop!"
    (let [stops (atom [])
          stop-args (atom {})
          get-contexts (atom {})]
      (clear!)
      (sys/set! ::test #{{:sys.component/id       :component-1
                          :sys.component/expects  #{}
                          :sys.component/provides #{:a}
                          :sys.component/start    (fn [_]
                                                    {:a 1})
                          :sys.component/stop    (fn [args]
                                                   (swap! stops conj :component-1)
                                                   (swap! stop-args assoc :component-1 args)
                                                   (swap! get-contexts assoc :component-1 (sys/context ::test)))}
                         {:sys.component/id       :component-2
                          :sys.component/expects  #{:a}
                          :sys.component/provides #{:b}
                          ;; testing not having a stop
                          :sys.component/start    (fn [{:keys [a]}]
                                                    {:b (+ a 1)})}
                         {:sys.component/id       :component-3
                          :sys.component/expects  #{:b}
                          :sys.component/provides #{:c}
                          :sys.component/start    (fn [{:keys [b]}]
                                                    {:c (+ b 1)})
                          :sys.component/stop    (fn [args]
                                                   (swap! stops conj :component-3)
                                                   (swap! stop-args assoc :component-3 args)
                                                   (swap! get-contexts assoc :component-3 (sys/context ::test)))}})

      (sys/start! ::test)
      (sys/stop! ::test)

      (testing "stops components in reverse order"
        (is (= [:component-3 :component-1] ;; component-2 doesn't have a stop fn
               @stops)))

      (testing "(internal) systems remains valid"
        (is (valid? i/Systems @sys/*systems)))

      (testing "each component receives what it provided"
        (is (= {:component-1 {:a 1}
                :component-3 {:c 3}}
               @stop-args)))

      (testing "context is updated after each stop"
        (is (= {:component-1 {:a 1}
                :component-3 {:a 1 :b 2 :c 3}}
               @get-contexts)))

      (testing "removes provided values from context"
        (is (= {} (sys/context ::test)))))

    (testing "removes provided keys from context even when component has no stop fn"
      (clear!)
      (sys/set! ::test #{{:sys.component/id      :component-1
                          :sys.component/provides #{:a}
                          :sys.component/start    (fn [_] {:a 1})}})
      (sys/start! ::test)
      (is (= {:a 1} (sys/context ::test)))
      (sys/stop! ::test)
      (is (= {} (sys/context ::test))))

    (testing "when given broken system only stops active components"
      (let [stops (atom [])]
        (clear!)
        (sys/set! ::test #{{:sys.component/id       :component-1
                            :sys.component/expects  #{}
                            :sys.component/provides #{:a}
                            :sys.component/start    (fn [_]
                                                      {:a 1})
                            :sys.component/stop    (fn [_]
                                                     (swap! stops conj :component-1))}
                           {:sys.component/id       :component-2
                            :sys.component/expects  #{:a}
                            :sys.component/provides #{:b}
                            :sys.component/start    (fn [{:keys [a]}]
                                                      {:b (+ a 1)})
                            :sys.component/stop    (fn [_]
                                                     (swap! stops conj :component-2))}
                           {:sys.component/id       :component-3
                            :sys.component/expects  #{:b}
                            :sys.component/provides #{:c}
                            :sys.component/start    (fn [{:keys [b]}]
                                                      (throw (ex-info "component failed to start" {}))
                                                      {:c (+ b 1)})
                            :sys.component/stop    (fn [_]
                                                     (swap! stops conj :component-3))}})
        (sys/start! ::test)
        (sys/stop! ::test)

        (testing "stops components in reverse order"
          (is (= [:component-2 :component-1]
                 @stops)))

        (testing "(internal) systems remains valid"
          (is (valid? i/Systems @sys/*systems))))))

  (testing "nested systems"
    (clear!)
    (sys/set! ::child #{#:sys.component
                         {:id       :child-1
                          :expects  #{}
                          :provides #{:a}
                          :start    (fn [_]
                                      {:a 1})}
                        #:sys.component
                         {:id       :child-2
                          :expects  {:a :int}
                          :provides {:b :int}
                          :start    (fn [{:keys [a]}]
                                      {:b (+ a 1)})}})
    (sys/set! ::parent #{#:sys.component
                          {:id       :parent-1
                           :expects  #{}
                           :provides #{:a}
                           :start    (fn [_]
                                       (sys/start! ::child)
                                       {:a 1})
                           :stop     (fn [_]
                                       (sys/stop! ::child))}

                         #:sys.component
                          {:id       :parent-2
                           :expects  {:a :int}
                           :provides {:b :int}
                           :start    (fn [{:keys [a]}]
                                       {:b (+ a 1)})}})

    (sys/start! ::parent)

    (testing "starting parent also starts child"
      (is (= {:a 1 :b 2} (sys/context ::child))))

    (testing "parent context is correct"
      (is (= {:a 1 :b 2} (sys/context ::parent))))

    (testing "(internal) systems remains valid after start"
      (is (valid? i/Systems @sys/*systems)))

    (testing "set! on running parent stops child and restarts it"
      (sys/set! ::parent #{#:sys.component
                            {:id       :parent-1
                             :expects  #{}
                             :provides #{:a}
                             :start    (fn [_]
                                         (sys/start! ::child)
                                         {:a 10})
                             :stop     (fn [_]
                                         (sys/stop! ::child))}

                           #:sys.component
                            {:id       :parent-2
                             :expects  {:a :int}
                             :provides {:b :int}
                             :start    (fn [{:keys [a]}]
                                         {:b (+ a 1)})}})

      (testing "child is restarted"
        (is (= {:a 1 :b 2} (sys/context ::child))))

      (testing "parent context reflects new components"
        (is (= {:a 10 :b 11} (sys/context ::parent))))

      (testing "(internal) systems remains valid after set!"
        (is (valid? i/Systems @sys/*systems))))

    (sys/stop! ::parent)

    (testing "stopping parent also stops child"
      (is (= {} (sys/context ::child))))

    (testing "parent context is cleared after stop"
      (is (= {} (sys/context ::parent))))

    (testing "(internal) systems remains valid after stop"
      (is (valid? i/Systems @sys/*systems)))))

(clojure.test/run-tests)

