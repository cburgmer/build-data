(ns build-facts.gocd.transform
  (:require [build-facts.data.junit-xml :as junit-xml]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [javax.xml.stream XMLStreamException]))

(defn- get-job-name [pipeline-name stage-name]
  (format "%s :: %s" pipeline-name stage-name))

(defn- get-build-id [pipeline-run stage-run]
  (if (= "1" stage-run)
    (str pipeline-run)
    (format "%s (Run %s)" pipeline-run stage-run)))


(defn- previous-stage-trigger [pipeline-name pipeline-run stage-name stages]
  (when-let [previous-stage (last (take-while #(not= stage-name (:name %)) stages))]
    [{:job-name (get-job-name pipeline-name (:name previous-stage))
      :build-id (get-build-id pipeline-run (:counter previous-stage))}]))


(defn- pipeline-build-cause [{:keys [modifications material changed]}]
  (when (and changed (= "Pipeline" (:type material)))
    (let [revision-tokens (str/split (:revision (first modifications)) #"/")
          pipeline-name (nth revision-tokens 0)
          pipeline-run (nth revision-tokens 1)
          stage-name (nth revision-tokens 2)
          stage-run (nth revision-tokens 3)]
      {:job-name (get-job-name pipeline-name stage-name)
       :build-id (get-build-id pipeline-run stage-run)})))

(defn- triggered-by-pipeline-run? [pipeline-instance]
  (not (:trigger_forced (:build_cause pipeline-instance))))

(defn- pipeline-material-triggers [pipeline-instance stages]
  (when (triggered-by-pipeline-run? pipeline-instance)
    (let [revisions (:material_revisions (:build_cause pipeline-instance))]
      (keep pipeline-build-cause revisions))))


(defn- first-stage? [stage-name stages]
  (= stage-name (:name (first stages))))

(defn- rerun? [stage-run]
  (not= "1" stage-run))

(defn- build-triggers-for-stage-instance [pipeline-name pipeline-run stage-name stage-run pipeline-instance]
  (let [stages (:stages pipeline-instance)]
    (if (first-stage? stage-name stages)
      (pipeline-material-triggers pipeline-instance stages)
      (when-not (rerun? stage-run)
        (previous-stage-trigger pipeline-name pipeline-run stage-name stages)))))


(defn- revision->input [{:keys [modifications material]}]
  {:revision (:revision (first modifications))
   :sourceId (str (:id material))})

(defn- inputs-for-stage-instance [pipeline-instance]
  (let [revisions (:material_revisions (:build_cause pipeline-instance))]
    (map revision->input revisions)))


(defn- aggregate-build-times [job-instances]
  (let [start-times (map :start job-instances)
        end-times (map :end job-instances)]
    (if (and (empty? (filter nil? end-times))
             (seq end-times))
      {:start (apply min start-times)
       :end (apply max end-times)}
      {})))

(defn- ignore-old-runs-for-rerun-stages [job-instances stage-run]
  (filter #(= stage-run (:actual-stage-run %)) job-instances))

(defn- aggregate-builds [stage-run job-instances]
  (let [outcomes (map :outcome job-instances)
        accumulated-outcome (if (every? #(= "pass" %) outcomes)
                              "pass"
                              "fail")]
    (-> job-instances
        (ignore-old-runs-for-rerun-stages stage-run)
        aggregate-build-times
        (assoc :outcome accumulated-outcome))))


(defn- aggregate-testresults [job-instances]
  (->> job-instances
       (mapcat :junit-xml)
       (remove nil?)))


(defn stage-instances->builds [{:keys [pipeline-name pipeline-run stage-name stage-run pipeline-instance job-instances]}]
  (let [job-name (get-job-name pipeline-name stage-name)
        build-id (get-build-id pipeline-run stage-run)
        inputs (inputs-for-stage-instance pipeline-instance)
        triggered-by (seq (build-triggers-for-stage-instance pipeline-name
                                                             pipeline-run
                                                             stage-name
                                                             stage-run
                                                             pipeline-instance))
        test-results (try
                       (->> (aggregate-testresults job-instances)
                            (mapcat junit-xml/parse-testsuites)
                            doall ; realise parsing errors while we can catch them
                            seq)
                       (catch javax.xml.stream.XMLStreamException e
                         (do
                           (log/errorf e "Unable parse JUnit XML from artifacts for %s %s." job-name build-id)
                           nil)))]
    (cond-> (merge {:job-name job-name
                    :build-id build-id}
                   (aggregate-builds stage-run job-instances))
      inputs (assoc :inputs inputs)
      triggered-by (assoc :triggered-by triggered-by)
      test-results  (assoc :test-results test-results))))
