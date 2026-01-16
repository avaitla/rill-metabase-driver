(ns metabase.driver.rill
  "Metabase driver for querying Rill APIs."
  (:require
   [cheshire.core :as json]
   [clj-http.client :as http]
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver-api.core :as driver-api]))

;; Register the driver
(driver/register! :rill)

;;; ---------------------------------------- Connection Helpers ----------------------------------------

(defn- get-host
  "Get the host URL from database details, ensuring no trailing slash."
  [database]
  (-> (get-in database [:details :host])
      (str/replace #"/$" "")))

(defn- get-instance
  "Get the instance name from database details."
  [database]
  (or (get-in database [:details :instance]) "default"))

(defn- api-url
  "Build a URL for a Rill API endpoint."
  [database & path-parts]
  (str (get-host database) (apply str path-parts)))

(defn- fetch-json
  "Make an HTTP GET request and parse the JSON response."
  [url]
  (-> (http/get url {:as :json
                     :throw-exceptions true
                     :socket-timeout 30000
                     :connection-timeout 10000})
      :body))

;;; ---------------------------------------- Type Inference ----------------------------------------

(defn- infer-base-type
  "Infer Metabase base type from a JSON value."
  [value]
  (cond
    (nil? value)     :type/Text
    (string? value)  :type/Text
    (boolean? value) :type/Boolean
    (integer? value) :type/Integer
    (float? value)   :type/Float
    (number? value)  :type/Float
    :else            :type/Text))

(defn- infer-fields-from-row
  "Infer field definitions from a single row of data."
  [row]
  (when (map? row)
    (set
     (for [[k v] row]
       {:name          (name k)
        :database-type "json"
        :base-type     (infer-base-type v)}))))

;;; ---------------------------------------- Driver Multimethods ----------------------------------------

(defmethod driver/display-name :rill [_]
  "Rill")

(defmethod driver/can-connect? :rill
  [_ details]
  (try
    (let [database {:details details}
          url (api-url database "/v1/instances/" (get-instance database))]
      (fetch-json url)
      true)
    (catch Exception _
      false)))

(defmethod driver/describe-database :rill
  [_ database]
  (let [instance (get-instance database)
        url (api-url database "/v1/instances/" instance "/resources")
        response (fetch-json url)
        resources (:resources response)
        ;; Filter for API resources only
        apis (filter #(= (:kind %) "rill.runtime.v1.API") resources)]
    {:tables
     (set
      (for [api apis
            :let [api-name (get-in api [:meta :name :name])]]
        {:name   api-name
         :schema nil}))}))

(defmethod driver/describe-table :rill
  [_ database {table-name :name}]
  (let [instance (get-instance database)
        url (api-url database "/v1/instances/" instance "/api/" table-name)
        response (fetch-json url)
        ;; Handle both array responses and wrapped responses
        data (cond
               (sequential? response) response
               (map? response) (or (:data response) [response])
               :else [])
        first-row (first data)
        fields (or (infer-fields-from-row first-row) #{})]
    {:name   table-name
     :schema nil
     :fields fields}))

(defmethod driver/database-supports? :rill
  [_ feature _]
  (contains?
   #{:basic-aggregations}
   feature))

(defmethod driver/mbql->native :rill
  [_ {database-id :database
      {source-table-id :source-table} :query}]
  (let [table (driver-api/table (driver-api/metadata-provider) source-table-id)
        table-name (:name table)]
    {:query table-name
     :database-id database-id}))

(defmethod driver/execute-reducible-query :rill
  [_ {native-query :native} _ respond]
  (let [database (driver-api/database (driver-api/metadata-provider))
        table-name (:query native-query)
        instance (get-instance database)
        url (api-url database "/v1/instances/" instance "/api/" table-name)
        response (fetch-json url)
        ;; Handle both array responses and wrapped responses
        data (cond
               (sequential? response) response
               (map? response) (or (:data response) [response])
               :else [])
        ;; Get column names from first row
        first-row (first data)
        columns (when (map? first-row) (keys first-row))
        col-names (mapv name columns)
        ;; Build rows as vectors in column order
        rows (mapv (fn [row]
                     (mapv #(get row %) columns))
                   data)]
    (respond
     {:cols (mapv (fn [col-name]
                    {:name col-name
                     :base_type :type/Text})
                  col-names)}
     rows)))
