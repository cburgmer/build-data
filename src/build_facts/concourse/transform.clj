(ns build-facts.concourse.transform
  (:require [cheshire.core :as j]))

(defn full-job-name [pipeline_name job_name]
  (format "%s %s" pipeline_name job_name))

(defn- unix-time-in-ms [timestamp]
  (when timestamp
    (* timestamp 1000)))

(defn- build-from [{:keys [pipeline_name job_name name status start_time end_time]}]
  {:job-name (full-job-name pipeline_name job_name)
   :build-id name
   :outcome (case status
              "succeeded" "pass"
              "failed" "fail"
              "aborted" "fail"
              "errored" "fail"
              "ongoing")
   :start (unix-time-in-ms (or start_time
                               end_time))
   :end (unix-time-in-ms end_time)})

(defn- concourse-input->input [{input-name :name version :version}]
  (let [sorted-version (into (sorted-map) version)]
    {:source-id (format "%s[%s]" input-name (->> (keys sorted-version)
                                                 (map name)
                                                 (clojure.string/join ",")))
     :revision (->> (vals sorted-version)
                    (map #(clojure.string/escape % {\, "%2C" \% "%25"}))
                    (clojure.string/join ","))}))

(defn- inputs-from [{inputs :inputs}]
  (map concourse-input->input inputs))

(defn- event-summary-reducer [summary event]
  (let [{{id :id} :origin time :time selected-worker :selected_worker} (:data (j/parse-string (:data event) true))]
    (if id
      (cond-> (update summary id merge {})
        time (update-in [id :first-event-time] (fn [first-event-time t] (or first-event-time t)) time)
        time (assoc-in [id :last-event-time] time)
        selected-worker (assoc-in [id :selected-worker] selected-worker))
      summary)))


(defn- steps [entry]
  (cond
    (:in_parallel entry) (->> (:steps (:in_parallel entry)) (mapcat steps))
    (:on_success entry) (steps (:step (:on_success entry)))
    (:get entry) [[(:id entry) (:name (:get entry))]]
    (:put entry) [[(:id entry) (:name (:put entry))]]
    :else [[(:id entry) (:name (:task entry))]]))

(defn- tasks-from [plan events]
  (let [event-summary (reduce event-summary-reducer {} events)]
    (->> plan
         (mapcat steps)
         (map (fn [[id name]]
                (let [{:keys [first-event-time last-event-time]} (get event-summary id)]
                  {:name name
                   :start (unix-time-in-ms first-event-time)
                   :end (unix-time-in-ms last-event-time)}))))))

(defn concourse->build [{:keys [build resources plan events]}]
  (let [inputs (seq (inputs-from resources))
        tasks (seq (tasks-from plan events))]
    (cond-> (build-from build)
      inputs (assoc :inputs inputs)
      tasks (assoc :tasks tasks))))
