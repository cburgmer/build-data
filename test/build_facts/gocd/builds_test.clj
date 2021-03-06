(ns build-facts.gocd.builds-test
  (:require [build-facts.gocd.builds :as sut]
            [build-facts.util.url :as url]
            [cheshire.core :as j]
            [clj-http.fake :as fake]
            [clj-time.core :as t]
            [clj-time.coerce :as tc]
            [clj-time.format :as tf]
            [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(defn- successful-json-response [body]
  (fn [_] {:status 200
           :body (j/generate-string body)}))

(defn- successful-response [body]
  (fn [_] {:status 200
           :body body}))

(defn- a-stage [name]
  {:name name})

(defn- a-pipeline [name & stages]
  {:name name
   :stages stages})

(defn- a-pipeline-group [name & pipelines]
  {:name name
   :pipelines pipelines})

(defn- a-config [& pipeline-groups]
  [["http://gocd:8513/api/config/pipeline_groups"
    (successful-json-response pipeline-groups)]])

(defn- a-job-run [name scheduled-date id]
  {:name name
   :scheduled_date scheduled-date
   :id id})

(defn- a-stage-run
  ([stage-name stage-run] {:name stage-name
                           :counter stage-run})
  ([pipeline-run stage-run & jobs] {:pipeline_counter pipeline-run
                                    :counter stage-run
                                    :jobs jobs}))

(defn- a-short-history [pipeline-name stage-name & stage-runs]
  [[(format "http://gocd:8513/api/stages/%s/%s/history/0" pipeline-name stage-name)
    (successful-json-response {:stages stage-runs})]
   [(format "http://gocd:8513/api/stages/%s/%s/history/%s" pipeline-name stage-name (count stage-runs))
    (successful-json-response {:stages '()})]])

(defn- a-simple-build-cause [revision id]
  {:modifications [{:revision revision}]
   :material {:id id}
   :changed false})

(defn- a-source-revision-build-cause [id revision]
  {:material {:id id :type "Git"}
   :modifications [{:revision revision}]
   :changed true})

(defn- a-pipeline-build-cause [id pipeline-name pipeline-run stage-name stage-run]
  {:material {:id id :type "Pipeline"}
   :modifications [{:revision (format "%s/%d/%s/%d" pipeline-name pipeline-run stage-name stage-run)}]
   :changed true})

(defn- a-pipeline-run [pipeline-name pipeline-run stages & revisions]
  [[(format "http://gocd:8513/api/pipelines/%s/instance/%s" pipeline-name pipeline-run)
    (successful-json-response {:stages stages
                               :build_cause {:material_revisions revisions
                                             :trigger_forced false}})]])

(defn- a-forced-pipeline-run [pipeline-name pipeline-run stages & revisions]
  [[(format "http://gocd:8513/api/pipelines/%s/instance/%s" pipeline-name pipeline-run)
    (successful-json-response {:stages stages
                               :build_cause {:material_revisions revisions
                                             :trigger_forced true}})]])

(defn- cruise-property [name value]
  (xml/element :property {:name name} (xml/->CData value)))

(defn- go-date-format [datetime]
  (tf/unparse (:date-time tf/formatters) datetime))

(defn- build-properties [{:keys [start-time end-time actual-stage-run outcome]}]
  (xml/emit-str (xml/element
                 :job {}
                 (xml/element
                  :properties {}
                  (cruise-property "cruise_job_result" outcome)
                  (cruise-property "cruise_timestamp_04_building" (go-date-format start-time))
                  (cruise-property "cruise_timestamp_06_completed" (go-date-format end-time))
                  (cruise-property "cruise_stage_counter" actual-stage-run)))))

(defn- a-builds-properties [job-id content]
  [[(format "http://gocd:8513/api/jobs/%s.xml" job-id)
    (successful-response (build-properties content))]])

(defn- no-build-properties [job-id]
  [[(format "http://gocd:8513/api/jobs/%s.xml" job-id)
    (successful-response (xml/emit-str (xml/element
                                        :job {}
                                        (xml/element
                                         :properties {} []))))]])

(defn- a-file-list [pipeline-name pipeline-run stage-name stage-run build-name & files]
  [[(format "http://gocd:8513/files/%s/%s/%s/%s/%s.json"
            pipeline-name pipeline-run stage-name stage-run build-name)
    (successful-json-response files)]])

(defn- a-file [pipeline-name pipeline-run stage-name stage-run build-name file-path content]
  [[(format "http://gocd:8513/files/%s/%s/%s/%s/%s/%s"
            pipeline-name pipeline-run stage-name stage-run build-name file-path)
    (successful-response content)]])

(defn- no-file-list [pipeline-name pipeline-run stage-name stage-run build-name]
  [[(format "http://gocd:8513/files/%s/%s/%s/%s/%s.json"
            pipeline-name pipeline-run stage-name stage-run build-name)
    (fn [_] {:status 404})]])

(defn- serve-up [& routes]
  (->> routes
       (mapcat identity)
       (into {})))


(deftest test-gocd-builds
  (testing "should handle no pipeline groups"
    (fake/with-fake-routes-in-isolation (serve-up (a-config))
      (is (empty? (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                    :pipeline-groups nil})))))

  (testing "should handle empty pipeline group"
    (fake/with-fake-routes-in-isolation (serve-up (a-config (a-pipeline-group "Development")))
      (is (empty? (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                    :pipeline-groups nil})))))

  (testing "should handle empty pipeline"
    (fake/with-fake-routes-in-isolation (serve-up (a-config (a-pipeline-group "Development"
                                                                              (a-pipeline "Build"))))
      (is (empty? (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                    :pipeline-groups nil})))))

  (testing "should sync a stage"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42
                                [(a-stage-run "DoStuff" "1")]
                                (a-simple-build-cause "AnotherPipeline/21" 7))
                (a-builds-properties 321
                                     {:start-time (t/date-time 2017 1 1 10 0 0)
                                      :end-time (t/date-time 2017 1 1 12 0)
                                      :outcome "Passed"
                                      :actual-stage-run "1"})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
      (is (= [["Build :: DoStuff"
               [{:job-name "Build :: DoStuff"
                 :build-id "42"
                 :start 1483264800000
                 :end 1483272000000
                 :outcome "pass"
                 :inputs [{:revision "AnotherPipeline/21", :sourceId "7"}]}]]]
             (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                               :pipeline-groups nil})))))

  (testing "should only sync given pipeline groups"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff")))
                          (a-pipeline-group "AnotherGroup"
                                            (a-pipeline "SomePipeline"
                                                        (a-stage "SomeStage"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-short-history "SomePipeline" "SomeStage"
                                 (a-stage-run 42 "1"
                                              (a-job-run "SomeJob" 1493201298062 456)))
                (a-pipeline-run "Build" 42
                                [(a-stage-run "DoStuff" "1")])
                (a-pipeline-run "SomePipeline" 42
                                [(a-stage-run "SomeStage" "1")])
                (a-builds-properties 321
                                     {:start-time (t/date-time 2017 1 1 10 0 0)
                                      :end-time (t/date-time 2017 1 1 12 0)
                                      :outcome "Passed"
                                      :actual-stage-run "1"})
                (a-builds-properties 456
                                     {:start-time (t/date-time 2017 1 1 10 0 0)
                                      :end-time (t/date-time 2017 1 1 12 0)
                                      :outcome "Passed"
                                      :actual-stage-run "1"})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob")
                (a-file-list "SomePipeline" 42 "SomeStage" "1" "SomeJob"))
      (is (= '("Build :: DoStuff")
             (->> (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                    :pipeline-groups '("Development")})
                  (map first))))))

  (testing "should sync a build trigger from another pipeline"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42
                                [(a-stage-run "DoStuff" "1")]
                                (a-pipeline-build-cause 7 "AnotherPipeline" 21 "AnotherStage" 2))
                (a-builds-properties 321
                                     {:start-time (t/date-time 2017 1 1 10 0 0)
                                      :end-time (t/date-time 2017 1 1 12 0)
                                      :outcome "Passed"
                                      :actual-stage-run "1"})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (= [{:job-name "AnotherPipeline :: AnotherStage"
               :build-id "21 (Run 2)"}]
               (:triggered-by build))))))

  (testing "should not sync a forced build trigger"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-forced-pipeline-run "Build" 42
                                       [(a-stage-run "DoStuff" "1")]
                                       (a-pipeline-build-cause 7 "AnotherPipeline" 21 "AnotherStage" 2))
                (a-builds-properties 321 {})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (nil? (:triggered-by build))))))

  (testing "should not count a source revision cause as pipeline trigger"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42
                                [(a-stage-run "DoStuff" "1")]
                                (a-source-revision-build-cause 7 "abcd"))
                (a-builds-properties 321
                                     {:start-time (t/date-time 2017 1 1 10 0 0)
                                      :end-time (t/date-time 2017 1 1 12 0)
                                      :outcome "Passed"
                                      :actual-stage-run "1"})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (nil? (:triggered-by build))))))

  (testing "should only sync build trigger from pipeline material for first stage"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff")
                                                        (a-stage "MoreStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1483261200000 321)))
                (a-short-history "Build" "MoreStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "defaultJob" 1483268400099 4711)))
                (a-pipeline-run "Build" 42
                                [(a-stage-run "DoStuff" "1") (a-stage-run "MoreStuff" "1")]
                                (a-pipeline-build-cause 7 "AnotherPipeline" 21 "AnotherStage" 2))
                (a-builds-properties 321 {})
                (a-builds-properties 4711 {})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob")
                (a-file-list "Build" 42 "MoreStuff" "1" "defaultJob"))
      (let [pipeline-trigger {:job-name "AnotherPipeline :: AnotherStage"
                              :build-id "21 (Run 2)"}
            builds (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                     :pipeline-groups nil})]
        (is (some #(= pipeline-trigger %)
                  (->> builds
                       (filter #(= (first %) "Build :: DoStuff"))
                       (map (fn [[_ [build]]] build))
                       first
                       :triggered-by)))
        (is (nil? (some #(= pipeline-trigger %)
                        (->> builds
                             (filter #(= (first %) "Build :: MoreStuff"))
                             (map (fn [[_ [build]]] build))
                             first
                             :triggered-by)))))))

  (testing "should sync build trigger from stage of same pipeline"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff")
                                                        (a-stage "MoreStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1483261200000 321)))
                (a-short-history "Build" "MoreStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "defaultJob" 1483268400099 4711)))
                (a-pipeline-run "Build" 42
                                [(a-stage-run "DoStuff" "1") (a-stage-run "MoreStuff" "1")])
                (a-builds-properties 321
                                     {:start-time (t/date-time 2017 1 1 10 0 0)
                                      :end-time (t/date-time 2017 1 1 12 0 0)
                                      :outcome "Passed"
                                      :actual-stage-run "1"})
                (a-builds-properties 4711
                                     {:start-time (t/date-time 2017 1 1 12 0 10)
                                      :end-time (t/date-time 2017 1 1 12 0 50)
                                      :outcome "Passed"
                                      :actual-stage-run "1"})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob")
                (a-file-list "Build" 42 "MoreStuff" "1" "defaultJob"))
      (is (= [{:job-name "Build :: DoStuff"
               :build-id "42"}]
             (->> (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                    :pipeline-groups nil})
                  (filter #(= (first %) "Build :: MoreStuff"))
                  (map (fn [[_ [build]]] build))
                  first
                  :triggered-by)))))

  (testing "should handle a rerun"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "2"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                (a-builds-properties 321
                                     {:start-time (t/date-time 2017 1 1 10 0 0)
                                      :end-time (t/date-time 2017 1 1 12 0)
                                      :outcome "Passed"
                                      :actual-stage-run "2"})
                (a-file-list "Build" 42 "DoStuff" "2" "AlphaJob"))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (= "42 (Run 2)"
               (:build-id build))))))

  (testing "should not sync build trigger for re-run of stage"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff")
                                                        (a-stage "MoreStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1483261200000 321)))
                (a-short-history "Build" "MoreStuff"
                                 (a-stage-run 42 "2"
                                              (a-job-run "defaultJob" 1483268400099 4711)))
                (a-pipeline-run "Build" 42
                                [(a-stage-run "DoStuff" "1") (a-stage-run "MoreStuff" "2")])
                (a-builds-properties 321
                                     {:start-time (t/date-time 2017 1 1 10 0 0)
                                      :end-time (t/date-time 2017 1 1 12 0 0)
                                      :outcome "Passed"
                                      :actual-stage-run "1"})
                (a-builds-properties 4711
                                     {:start-time (t/date-time 2017 1 1 12 0 10)
                                      :end-time (t/date-time 2017 1 1 12 0 50)
                                      :outcome "Passed"
                                      :actual-stage-run "1"})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob")
                (a-file-list "Build" 42 "MoreStuff" "2" "defaultJob"))
      (is (nil? (->> (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                       :pipeline-groups nil})
                     (filter #(= (first %) "Build :: MoreStuff"))
                     (map (fn [[_ [build]]] build))
                     first
                     :triggered-by)))))

  (testing "should sync a failing stage"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                (a-builds-properties 321
                                     {:start-time (t/date-time 2017 1 1 10 0 0)
                                      :end-time (t/date-time 2017 1 1 12 0)
                                      :outcome "Failed"
                                      :actual-stage-run "1"})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"))
      (is (= [["Build :: DoStuff"
               [{:job-name "Build :: DoStuff"
                 :build-id "42"
                 :start 1483264800000
                 :end 1483272000000
                 :outcome "fail"
                 :inputs []}]]]
             (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                               :pipeline-groups nil})))))

  (testing "should support an ongoing stage"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                (no-build-properties 321)
                (no-file-list "Build" 42 "DoStuff" 1 "AlphaJob"))
      (is (= [["Build :: DoStuff"
               [{:job-name "Build :: DoStuff"
                 :build-id "42"
                 :outcome "ongoing"
                 :inputs []}]]]
             (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                               :pipeline-groups nil})))))

  (testing "should sync test results"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                (a-builds-properties 321 {})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                             {:files [{:name "dontcare.log"
                                       :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/dontcare.log"}
                                      {:name "results.xml"
                                       :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}]})
                (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                        "<testsuite name=\"my-suite\"><testcase classname=\"my-class\" name=\"my-name\" time=\"0.042\"/></testsuite>"))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (= '({:name "my-suite"
                :children ({:classname "my-class"
                            :name "my-name"
                            :runtime 42
                            :status "pass"})})
               (:test-results build))))))

  (testing "should sync multiple test results in one job"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                (a-builds-properties 321 {})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                             {:name "one_result.xml"
                              :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/one_result.xml"}
                             {:name "others.xml"
                              :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/others.xml"})
                (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "one_result.xml"
                        "<testsuite name=\"one\"></testsuite>")
                (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "others.xml"
                        "<testsuites><testsuite name=\"other\"></testsuite></testsuites>"))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (= '({:name "one"
                  :children []}
                 {:name "other"
                  :children []})
               (:test-results build))))))

  (testing "should combine test results for two jobs"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)
                                              (a-job-run "BetaJob" 1493201298062 987)))
                (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                (a-builds-properties 321 {})
                (a-builds-properties 987 {})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                             {:files [{:name "results.xml"
                                       :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}]})
                (a-file-list "Build" 42 "DoStuff" "1" "BetaJob"
                             {:files [{:name "results.xml"
                                       :url "http://example.com/something/files/Build/42/DoStuff/1/BetaJob/tmp/results.xml"}]})
                (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                        "<testsuites><testsuite name=\"Alpha\"></testsuite></testsuites>")
                (a-file "Build" 42 "DoStuff" "1" "BetaJob" "tmp/results.xml"
                        "<testsuites><testsuite name=\"Beta\"></testsuite></testsuites>"))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (= '({:name "Alpha"
                  :children []}
                 {:name "Beta"
                  :children []})
               (:test-results build))))))

  (testing "should store test results even if one job has no XML"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)
                                              (a-job-run "BetaJob" 1493201298062 987)))
                (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                (a-builds-properties 321 {})
                (a-builds-properties 987 {})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                             {:files [{:name "results.xml"
                                       :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}]})
                (a-file-list "Build" 42 "DoStuff" "1" "BetaJob")
                (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                        "<testsuites><testsuite name=\"Alpha\"></testsuite></testsuites>"))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (= '({:name "Alpha"
                  :children []})
               (:test-results build))))))

  (testing "should not include non-JUnit-XML file"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                (a-builds-properties 321 {})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                             {:files [{:name "nontest.xml"
                                       :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/nontest.xml"}
                                      {:name "results.xml"
                                       :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}]})
                (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/nontest.xml"
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><someNode><contentNode></contentNode></someNode>")
                (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?> <!-- comments are fine -->  <testsuites><testsuite name=\"Alpha\"></testsuite></testsuites>"))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (= '({:name "Alpha"
                  :children []})
               (:test-results build))))))

  (testing "should not error on invalid JUnit XML"
    (fake/with-fake-routes-in-isolation
      (serve-up (a-config (a-pipeline-group "Development"
                                            (a-pipeline "Build"
                                                        (a-stage "DoStuff"))))
                (a-short-history "Build" "DoStuff"
                                 (a-stage-run 42 "1"
                                              (a-job-run "AlphaJob" 1493201298062 321)))
                (a-pipeline-run "Build" 42 [(a-stage-run "DoStuff" "1")])
                (a-builds-properties 321 {})
                (a-file-list "Build" 42 "DoStuff" "1" "AlphaJob"
                             {:files [{:name "results.xml"
                                       :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/results.xml"}
                                      {:name "problematic-results.xml"
                                       :url "http://example.com/something/files/Build/42/DoStuff/1/AlphaJob/tmp/problematic-results.xml"}]})
                (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/results.xml"
                        "<testsuite name=\"valid-suite\"/>")
                (a-file "Build" 42 "DoStuff" "1" "AlphaJob" "tmp/problematic-results.xml"
                        "<testsuite name=\"invalid-suite\"><testcase "))
      (let [[[_ [build]]] (sut/gocd-builds {:base-url (url/url "http://gocd:8513")
                                            :pipeline-groups nil})]
        (is (nil? (:test-results build)))))))
