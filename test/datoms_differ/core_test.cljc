(ns datoms-differ.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [datoms-differ.core :as sut]))

(def schema
  {:route/name {}
   :route/number {:db/unique :db.unique/identity}
   :route/services {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many}
   :service/trips {:db/valueType :db.type/ref :db/cardinality :db.cardinality/many :db/isComponent true}
   :trip/id {:db/unique :db.unique/identity}
   :service/id {:db/unique :db.unique/identity}
   :service/label {}
   :service/allocated-vessel {:db/valueType :db.type/ref :db/cardinality :db.cardinality/one}
   :vessel/imo {:db/unique :db.unique/identity}
   :vessel/name {}})

(deftest finds-attrs
  (is (= (sut/find-attrs schema)
         {:identity? #{:route/number :service/id :vessel/imo :trip/id}
          :ref? #{:route/services :service/allocated-vessel :service/trips}
          :many? #{:route/services :service/trips}
          :component? #{:service/trips}})))

(def attrs (sut/find-attrs schema))

(deftest gets-entity-refs
  (is (= (sut/get-entity-ref attrs {:route/number "100" :route/name "Stavanger-Tau"})
         [:route/number "100"]))

  (is (thrown? Exception ;; multiple identity attributes
               (sut/get-entity-ref attrs {:route/number "100" :service/id 200})))

  (is (thrown? Exception ;; no identity attributes
               (sut/get-entity-ref attrs {:route/name "Stavanger-Tau"})))

  (is (= (sut/get-entity-ref attrs {:db/id 123456 :route/name "100"})
         [:db/id 123456])))

(deftest finds-all-entities
  (is (= (sut/find-all-entities attrs [{:route/number "100"
                                        :route/services [{:service/id :s567
                                                          :service/allocated-vessel {:vessel/imo "123"}}]}])
         [{:route/number "100"
           :route/services [{:service/id :s567
                             :service/allocated-vessel {:vessel/imo "123"}}]}
          {:service/id :s567
           :service/allocated-vessel {:vessel/imo "123"}}
          {:vessel/imo "123"}]))

  (is (= (sut/find-all-entities attrs [{:vessel/imo "123"
                                        :service/_allocated-vessel [{:service/id :s567}]}])
         [{:vessel/imo "123"
           :service/_allocated-vessel [{:service/id :s567}]}
          {:service/id :s567}])))

(deftest creates-refs-lookup
  (testing "creates new eids for unknown entity refs"
    (is (= (sut/create-refs-lookup {[:route/number "100"] 1024
                                    [:vessel/imo "123"] 1025}
                                   [[:route/number "100"]
                                    [:vessel/imo "123"]
                                    [:service/id :s567]])
           {[:route/number "100"] 1024
            [:vessel/imo "123"] 1025
            [:service/id :s567] 1026})))

  (testing "uses db/id when given"
    (is (= (sut/create-refs-lookup {[:route/number "100"] 1024
                                    [:vessel/imo "123"] 1025}
                                   [[:route/number "100"]
                                    [:vessel/imo "123"]
                                    [:db/id 99999]])
           {[:route/number "100"] 1024
            [:vessel/imo "123"] 1025
            [:db/id 99999] 99999}))))

(deftest flattens-entity-map
  (is (= (sut/flatten-entity-map attrs
                                 {[:route/number "100"] 1024
                                  [:vessel/imo "123"] 1025
                                  [:service/id :s567] 1026}
                                 {:route/number "100"
                                  :route/name "Stavanger-Tau"})
         [[1024 :route/number "100"]
          [1024 :route/name "Stavanger-Tau"]]))

  ;; reverse entity ref, isn't component
  (is (= (sut/flatten-entity-map attrs
                                 {[:service/id :s567] 1026
                                  [:vessel/imo "123"] 1024}
                                 {:vessel/imo "123"
                                  :service/_allocated-vessel [{:service/id :s567}]})
         [[1024 :vessel/imo "123"]
          [1026 :service/allocated-vessel 1024]]))

  ;; reverse entity ref, is component
  (is (= (sut/flatten-entity-map attrs
                                 {[:service/id :s567] 1026
                                  [:trip/id "foo"] 1025}
                                 {:trip/id "foo"
                                  :service/_trips {:service/id :s567}})
         [[1025 :trip/id "foo"]
          [1026 :service/trips 1025]]))

  (is (= (sut/flatten-entity-map attrs
                                 {[:route/number "100"] 1024
                                  [:vessel/imo "123"] 1025
                                  [:service/id :s567] 1026}
                                 {:route/number "100"
                                  :route/services [{:service/id :s567
                                                    :service/allocated-vessel {:vessel/imo "123"}}]})
         [[1024 :route/number "100"]
          [1024 :route/services 1026]]))

  (is (= (sut/flatten-entity-map attrs
                                 {[:db/id 99999] 99999}
                                 {:db/id 99999
                                  :route/name "Stavanger-Tau"})
         [[99999 :route/name "Stavanger-Tau"]]))

  (is (thrown? Exception ;; no nil vals
               (sut/flatten-entity-map attrs
                                       {[:route/number "100"] 1024
                                        [:vessel/imo "123"] 1025
                                        [:service/id :s567] 1026}
                                       {:route/number "100"
                                        :route/name nil}))))

(deftest explodes
  (testing "no existing refs, simple case"
    (is (= (sut/explode {:schema schema :refs {}}
                        [{:route/number "100"}])
           {:refs {[:route/number "100"] 1024}
            :datoms #{[1024 :route/number "100"]}})))

  (testing "no existing refs, interesting case"
    (is (= (sut/explode {:schema schema :refs {}}
                        [{:route/number "100"
                          :route/services [{:service/id :s567
                                            :service/allocated-vessel {:vessel/imo "123"}}
                                           {:service/id :s789}]}])
           {:refs {[:route/number "100"] 1024
                   [:service/id :s567] 1025
                   [:service/id :s789] 1026
                   [:vessel/imo "123"] 1027}
            :datoms #{[1024 :route/number "100"]
                      [1024 :route/services 1025]
                      [1024 :route/services 1026]
                      [1025 :service/id :s567]
                      [1025 :service/allocated-vessel 1027]
                      [1026 :service/id :s789]
                      [1027 :vessel/imo "123"]}})))

  (testing "with db/id"
    (is (= (sut/explode {:schema schema :refs {}}
                        [{:db/id 99999
                          :route/name "Stavanger-Tau"}])
           {:refs {[:db/id 99999] 99999}
            :datoms #{[99999 :route/name "Stavanger-Tau"]}})))

  (testing "reverse refs"
    (is (= {:refs {[:route/number "100"] 1027
                   [:service/id :s567] 1026
                   [:service/id :s789] 1028
                   [:trip/id "foo"] 1025
                   [:trip/id "bar"] 1029
                   [:vessel/imo "123"] 1024}
            :datoms #{[1024 :vessel/imo "123"]
                      [1025 :trip/id "foo"]
                      [1029 :trip/id "bar"]
                      [1026 :service/allocated-vessel 1024]
                      [1026 :service/id :s567]
                      [1027 :route/number "100"]
                      [1027 :route/services 1026]
                      [1027 :route/services 1028]
                      [1028 :service/id :s789]
                      [1028 :service/trips 1025]
                      [1028 :service/trips 1029]}}
           (sut/explode {:schema schema :refs {}}
                        [{:vessel/imo "123"
                          :service/_allocated-vessel [{:service/id :s567
                                                       :route/_services #{{:route/number "100"}}}]}
                         {:trip/id "foo"
                          :service/_trips {:service/id :s789
                                           :route/_services #{{:route/number "100"}}
                                           :service/trips #{{:trip/id "bar"}}}}]))))

  (testing "existing refs"
    (is (= (sut/explode {:schema schema :refs {[:route/number "100"] 2024
                                               [:service/id :s567] 2025}}
                        [{:route/number "100"
                          :route/services [{:service/id :s567
                                            :service/allocated-vessel {:vessel/imo "123"}}]}])
           {:refs {[:route/number "100"] 2024
                   [:service/id :s567] 2025
                   [:vessel/imo "123"] 2026}
            :datoms #{[2024 :route/number "100"]
                      [2024 :route/services 2025]
                      [2025 :service/id :s567]
                      [2025 :service/allocated-vessel 2026]
                      [2026 :vessel/imo "123"]}})))

  (testing "conflicting values for entity attr"
    (is (thrown? Exception ;; different values for same entity attribute
                 (sut/explode {:schema schema :refs {}}
                              [{:route/number "100" :route/name "Stavanger-Taua"}
                               {:route/number "100" :route/name "Stavanger-Tau"}]))))

  (testing "no attrs asserted for entity"
    (is (thrown? Exception
                 (sut/explode {:schema schema :refs {}}
                              [{:db/id 999999}])))))

(deftest diffs
  (is (= (sut/diff #{}
                   #{[2025 :service/id :s567]
                     [2025 :service/allocated-vessel 2026]
                     [2026 :vessel/imo "123"]})
         [[:db/add 2025 :service/id :s567]
          [:db/add 2025 :service/allocated-vessel 2026]
          [:db/add 2026 :vessel/imo "123"]]))

  (is (= (sut/diff #{[2025 :service/id :s567]
                     [2025 :service/allocated-vessel 2026]
                     [2026 :vessel/imo "123"]}
                   #{})
         [[:db/retract 2025 :service/id :s567]
          [:db/retract 2025 :service/allocated-vessel 2026]
          [:db/retract 2026 :vessel/imo "123"]]))

  (is (= (sut/diff #{[2024 :service/id :s345] [2025 :service/id :s567] [2025 :service/allocated-vessel 2026] [2026 :vessel/imo "123"]}
                   #{[2024 :service/id :s345] [2025 :service/id :s567] [2024 :service/allocated-vessel 2026] [2026 :vessel/imo "123"]})
         [[:db/retract 2025 :service/allocated-vessel 2026]
          [:db/add 2024 :service/allocated-vessel 2026]])))

(deftest with-keeps-track-of-different-sources
  (let [db-at-first (sut/empty-db schema)
        tx-sporadic-1 [{:route/number "100"
                        :route/services [{:service/id :s567
                                          :service/allocated-vessel {:vessel/imo "123"}}]}]
        refs {[:route/number "100"] 1024
              [:service/id :s567] 1025
              [:vessel/imo "123"] 1026}

        sporadic-1-datoms #{[1024 :route/number "100"]
                            [1024 :route/services 1025]
                            [1025 :service/id :s567]
                            [1025 :service/allocated-vessel 1026]
                            [1026 :vessel/imo "123"]}

        tx-data-sporadic-1 #{[:db/add 1024 :route/number "100"]
                             [:db/add 1024 :route/services 1025]
                             [:db/add 1025 :service/id :s567]
                             [:db/add 1025 :service/allocated-vessel 1026]
                             [:db/add 1026 :vessel/imo "123"]}

        db-after-sporadic-1 {:schema schema
                             :refs refs
                             :source-datoms {:sporadic sporadic-1-datoms}}

        tx-frequent-1 [{:vessel/imo "123" :vessel/name "Jekyll"}]

        tx-data-frequent-1 #{[:db/add 1026 :vessel/name "Jekyll"]}

        db-after-frequent-1 {:schema schema
                             :refs refs
                             :source-datoms {:sporadic sporadic-1-datoms
                                             :frequent #{[1026 :vessel/imo "123"]
                                                         [1026 :vessel/name "Jekyll"]}}}

        tx-frequent-2 []

        tx-data-frequent-2 #{[:db/retract 1026 :vessel/name "Jekyll"]}

        db-after-frequent-2 {:schema schema
                             :refs refs
                             :source-datoms {:sporadic sporadic-1-datoms
                                             :frequent #{}}}

        tx-sporadic-2 [{:route/number "100"
                        :route/services [{:service/id :s567}]}]

        tx-data-sporadic-2 #{[:db/retract 1025 :service/allocated-vessel 1026]
                             [:db/retract 1026 :vessel/imo "123"]}

        db-after-sporadic-2 {:schema schema
                             :refs refs
                             :source-datoms {:sporadic #{[1024 :route/number "100"]
                                                         [1024 :route/services 1025]
                                                         [1025 :service/id :s567]}
                                             :frequent #{}}}]
    (is (= (-> (sut/with db-at-first :sporadic tx-sporadic-1)
               (update :tx-data set))
           {:tx-data tx-data-sporadic-1
            :db-before db-at-first
            :db-after db-after-sporadic-1}))

    (is (= (-> (sut/with db-after-sporadic-1 :frequent tx-frequent-1)
               (update :tx-data set))
           {:tx-data tx-data-frequent-1
            :db-before db-after-sporadic-1
            :db-after db-after-frequent-1}))

    (is (= (-> (sut/with db-after-frequent-1 :frequent tx-frequent-2)
               (update :tx-data set))
           {:tx-data tx-data-frequent-2
            :db-before db-after-frequent-1
            :db-after db-after-frequent-2}))

    (is (= (-> (sut/with db-after-frequent-2 :sporadic tx-sporadic-2)
               (update :tx-data set))
           {:tx-data tx-data-sporadic-2
            :db-before db-after-frequent-2
            :db-after db-after-sporadic-2}))))

(deftest with-supports-partial-updates
  (let [db-at-first (sut/empty-db schema)
        tx-1 [{:route/number "100"
               :route/services [{:service/id :s567
                                 :service/allocated-vessel {:vessel/imo "123"}}]}]
        refs {[:route/number "100"] 1024
              [:service/id :s567] 1025
              [:vessel/imo "123"] 1026}

        tx-1-datoms #{[1024 :route/number "100"]
                      [1024 :route/services 1025]
                      [1025 :service/id :s567]
                      [1025 :service/allocated-vessel 1026]
                      [1026 :vessel/imo "123"]}

        tx-1-data #{[:db/add 1024 :route/number "100"]
                    [:db/add 1024 :route/services 1025]
                    [:db/add 1025 :service/id :s567]
                    [:db/add 1025 :service/allocated-vessel 1026]
                    [:db/add 1026 :vessel/imo "123"]}

        db-after-tx-1 {:schema schema
                       :refs refs
                       :source-datoms {:source tx-1-datoms}}

        tx-2 (with-meta
               [{:route/number "100"
                 :route/name "Stavanger-Tau"}]
               {:partial-update? true})

        tx-2-datoms #{[1024 :route/number "100"]
                      [1024 :route/name "Stavanger-Tau"]
                      [1024 :route/services 1025]
                      [1025 :service/id :s567]
                      [1025 :service/allocated-vessel 1026]
                      [1026 :vessel/imo "123"]}

        tx-2-data #{[:db/add 1024 :route/name "Stavanger-Tau"]}

        db-after-tx-2 {:schema schema
                       :refs refs
                       :source-datoms {:source tx-2-datoms}}]

    (is (= (-> (sut/with db-at-first :source tx-1)
               (update :tx-data set))
           {:tx-data tx-1-data
            :db-before db-at-first
            :db-after db-after-tx-1}))

    (is (= (-> (sut/with db-after-tx-1 :source tx-2)
               (update :tx-data set))
           {:tx-data tx-2-data
            :db-before db-after-tx-1
            :db-after db-after-tx-2}))))
