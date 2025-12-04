(ns sys.test.api
  (:require
   [clojure.test :refer [deftest testing is] :as t]
   [malli.core :as m]
   [malli.error :as me]
   [sys.api :as sys]))

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

(deftest e2e
  (testing "init!"

    (testing
      "returns system atom"
      (let [components #{#:sys.component
                         {:id       :component-1
                          :expects  #{}
                          :provides #{:a}
                          :start    (fn [_]
                                      {:a 1})}}
            system (sys/init! components)]
        (is (= clojure.lang.Atom (type system)))
        (is (valid? sys/SystemObject @system))
        (testing "(internal) init-components are set"
          (is (= components
                 (::sys/init-components @system))))))

    (testing "throws exception when component definitions are invalid"
      (is (thrown-with-msg?
           java.lang.AssertionError
           #"m/validate"
           (sys/init! #{{}}))))

    (testing "throws exception when dependencies are not met"
      (is (thrown-with-msg?
           java.lang.AssertionError
           #"all-expects-provided"
           (sys/init! #{{:sys.component/id       :component-1
                         :sys.component/provides #{:a}}
                        {:sys.component/id       :component-2
                         :sys.component/expects  #{:b}}}))))

    (testing "throws exception when there are circular dependencies"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Cycle Detected"
           (sys/init! #{{:sys.component/id       :component-1
                         :sys.component/expects  #{:b}
                         :sys.component/provides #{:a}}
                        {:sys.component/id       :component-2
                         :sys.component/expects  #{:a}
                         :sys.component/provides #{:b}}}))))

    (testing "throws exception when multiple components provide same key"
      (is (thrown-with-msg?
           java.lang.AssertionError
           #"all-provides-unique"
           (sys/init! #{{:sys.component/id       :component-1
                         :sys.component/provides #{:a}}
                        {:sys.component/id       :component-2
                         :sys.component/provides #{:a}}})))))

  (testing "start!"
    (let [starts (atom [])
          start-args (atom {})
          components #{{:sys.component/id       :component-1
                        :sys.component/expects  #{}
                        :sys.component/provides #{:a}
                        :sys.component/start    (fn [args]
                                                  (swap! starts conj :component-1)
                                                  (swap! start-args assoc :component-1 args)
                                                  {:a 1})}
                       {:sys.component/id       :component-2
                        :sys.component/expects  #{:a}
                        :sys.component/provides #{:b}
                        :sys.component/start    (fn [{:keys [a] :as args}]
                                                  (swap! starts conj :component-2)
                                                  (swap! start-args assoc :component-2 args)
                                                  {:b (+ a 1)})}
                       {:sys.component/id       :component-3
                        :sys.component/expects  #{:b}
                        :sys.component/provides #{:c}
                        :sys.component/start    (fn [{:keys [b] :as args}]
                                                  (swap! starts conj :component-3)
                                                  (swap! start-args assoc :component-3 args)
                                                  {:c (+ b 1)})}}
          system (-> (sys/init! components)
                     sys/start!)]

      (testing "calls start on all components, in dependency order"
        (is (= [:component-1 :component-2 :component-3] @starts)))

      (testing "returns a valid system"
        (is (valid? sys/SystemObject @system)))

      (testing "each component receives the values it expects"
        (is (= {:component-1 {}
                :component-2 {:a 1}
                :component-3 {:b 2}}
               @start-args)))

      (testing "resulting system has all defined keys"
        (is (= 1 (sys/get system :a)))
        (is (= 2 (sys/get system :b)))
        (is (= 3 (sys/get system :c))))

      (testing "repeating start on started system does not call start functions again"
        (sys/start! system)
        (is (= [:component-1 :component-2 :component-3] @starts))
        (testing "returns a valid system"
          (is (valid? sys/SystemObject @system)))))

    (testing "throws when a component does not provide what it declared"
      (let [system (-> (sys/init! #{{:sys.component/id       :component-1
                                     :sys.component/provides #{:a}
                                     :sys.component/start    (fn [_]
                                                               {:b 1})}})
                       sys/start!)]
        (is (= (-> system
                   (sys/get ::sys/exception)
                   ex-message)
               "Component with id :component-1 did not provide (:a) as declared."))

        (testing "returns a valid system"
          (is (valid? sys/SystemObject @system)))))

    (testing "when a component fails to start does not start remaining components"
      (let [starts (atom [])
            component-state (atom :broken)
            components #{{:sys.component/id       :component-1
                          :sys.component/expects  #{}
                          :sys.component/provides #{:a}
                          :sys.component/start    (fn [_]
                                                    (swap! starts conj :component-1)
                                                    {:a 1})}
                         {:sys.component/id       :component-2
                          :sys.component/expects  #{:a}
                          :sys.component/provides #{:b}
                          :sys.component/start    (fn [{:keys [a]}]
                                                    (swap! starts conj :component-2)
                                                    (case @component-state
                                                      :broken (throw (ex-info "component failed to start" {}))
                                                      :fixed nil)
                                                    {:b (+ a 1)})}
                         {:sys.component/id       :component-3
                          :sys.component/expects  #{:b}
                          :sys.component/provides #{:c}
                          :sys.component/start    (fn [{:keys [b]}]
                                                    (swap! starts conj :component-3)
                                                    {:c (+ b 1)})}}
            system (-> (sys/init! components)
                       sys/start!)]
        (is (= [:component-1 :component-2] @starts))

        (testing "returns a valid system"
          (is (valid? sys/SystemObject @system)))

        (testing "resulting system has all defined keys"
          (is (= 1 (sys/get system :a))))

        (testing "calling start! with a broken system resumes starting from the broken component"
          (reset! component-state :fixed)
          (sys/start! system)
          (is (= [;; from before
                  :component-1 :component-2
                  ;; component-2 is started a second time
                  :component-2 :component-3] @starts))
          (testing "returns a valid system"
            (is (valid? sys/SystemObject @system)))))))

  (testing "stop!"
    (let [stops (atom [])
          stop-args (atom {})
          components #{{:sys.component/id       :component-1
                        :sys.component/expects  #{}
                        :sys.component/provides #{:a}
                        :sys.component/start    (fn [_]
                                                  {:a 1})
                        :sys.component/stop    (fn [args]
                                                 (swap! stops conj :component-1)
                                                 (swap! stop-args assoc :component-1 args))}
                       {:sys.component/id       :component-2
                        :sys.component/expects  #{:a}
                        :sys.component/provides #{:b}
                        :sys.component/start    (fn [{:keys [a]}]
                                                  {:b (+ a 1)})
                        :sys.component/stop    (fn [args]
                                                 (swap! stops conj :component-2)
                                                 (swap! stop-args assoc :component-2 args))}
                       {:sys.component/id       :component-3
                        :sys.component/expects  #{:b}
                        :sys.component/provides #{:c}
                        :sys.component/start    (fn [{:keys [b]}]
                                                  {:c (+ b 1)})
                        :sys.component/stop    (fn [args]
                                                 (swap! stops conj :component-3)
                                                 (swap! stop-args assoc :component-3 args))}}
          system (-> (sys/init! components)
                     sys/start!
                     sys/stop!)]
      (testing "stops components in reverse order"
        (is (= [:component-3 :component-2 :component-1]
               @stops)))
      (testing "returns a valid system"
        (is (valid? sys/SystemObject @system)))

      (testing "each component receives what it provided"

        (is (= {:component-1 {:a 1}
                :component-2 {:b 2}
                :component-3 {:c 3}}
               @stop-args))))
    (testing "when given broken system only stops active components"
      (let [stops (atom [])
            components #{{:sys.component/id       :component-1
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
                                                   (swap! stops conj :component-3))}}
            system (-> (sys/init! components)
                       sys/start!
                       sys/stop!)]
        (testing "stops components in reverse order"
          (is (= [:component-2 :component-1]
                 @stops)))
        (testing "returns a valid system"
          (is (valid? sys/SystemObject @system)))))))

(clojure.test/run-tests)

