(ns build-facts.concourse.transform-test
  (:require [build-facts.concourse.transform :as sut]
            [clojure.test :refer :all]))

(deftest test-concourse-transform
  (testing "handles inputs with multiple keys"
    (is (= [{:source-id "something[another-key,key1]"
             :revision "more val,some val"}]
           (:inputs (sut/concourse->build {:build {}
                                           :resources {:inputs [{:name "something"
                                                                 :version {:key1 "some val"
                                                                           :another-key "more val"}}]}})))))
  (testing "should not conflate different version with similar values"
    (let [inputs (:inputs (sut/concourse->build {:build {}
                                                 :resources {:inputs [{:name "first"
                                                                       :version {:a ","
                                                                                 :b ""}}
                                                                      {:name "second"
                                                                       :version {:a ""
                                                                                 :b ","}}]}}))]
      (is (not= (:revision (first inputs))
                (:revision (second inputs))))))
  (testing "should escape soundly"
    (is (= "%252C,%2C"
           (-> (sut/concourse->build {:build {}
                                      :resources {:inputs [{:name "first"
                                                            :version {:a "%2C"
                                                                      :b ","}}]}})
               :inputs
               first
               :revision)))))