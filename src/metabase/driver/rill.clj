(ns metabase.driver.rill
  "Metabase driver for querying Rill APIs with JSON query interface."
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
  "Make an HTTP GET request with optional query params and parse the JSON response."
  ([url]
   (fetch-json url nil))
  ([url query-params]
   (-> (http/get url {:as :json
                      :throw-exceptions true
                      :socket-timeout 30000
                      :connection-timeout 10000
                      :query-params query-params})
       :body)))

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

;;; ---------------------------------------- Query Parsing ----------------------------------------

(defn- parse-query
  "Parse a native query string. Supports:
   - JSON object: {\"api\": \"name\", \"params\": {...}, \"filter\": {...}}
   - Plain API name string: \"metrics_api\"

   Query format:
   {
     \"api\": \"metrics_margin_api\",     // Required: API endpoint name
     \"params\": {                        // Optional: Query params sent to API
       \"customer\": \"Acme\",
       \"limit\": 100
     },
     \"filter\": {                        // Optional: Client-side filtering
       \"status\": \"active\",
       \"revenue\": {\"gte\": 1000}
     },
     \"fields\": [\"name\", \"revenue\"], // Optional: Select specific fields
     \"limit\": 50                        // Optional: Limit results
   }

   Returns a map with :api, :params, :filter, :fields, :limit keys."
  [query-string]
  (let [trimmed (str/trim (or query-string ""))]
    (if (str/starts-with? trimmed "{")
      ;; JSON query format
      (let [parsed (json/parse-string trimmed true)]
        {:api    (or (:api parsed) (:table parsed) (:endpoint parsed))
         :params (:params parsed)
         :filter (:filter parsed)
         :fields (:fields parsed)
         :limit  (:limit parsed)})
      ;; Plain API name (backwards compatible)
      {:api trimmed
       :params nil
       :filter nil
       :fields nil
       :limit nil})))

(defn- matches-filter?
  "Check if a row matches the filter criteria.
   Filter format: {\"field\": \"value\"} or {\"field\": {\"op\": \"value\"}}

   Supported operators:
   - eq: equals
   - ne: not equals
   - gt, gte, lt, lte: numeric comparisons
   - contains: string contains
   - starts_with: string starts with
   - ends_with: string ends with
   - in: value in array"
  [row filter-map]
  (if (empty? filter-map)
    true
    (every?
     (fn [[field condition]]
       (let [field-key (keyword field)
             value (get row field-key)]
         (cond
           ;; Simple equality: {"field": "value"}
           (not (map? condition))
           (= value condition)

           ;; Complex condition: {"field": {"eq": "value"}}
           :else
           (let [{:keys [eq ne gt gte lt lte contains starts_with ends_with in]} condition]
             (cond
               (some? eq)  (= value eq)
               (some? ne)  (not= value ne)
               (some? gt)  (and (number? value) (> value gt))
               (some? gte) (and (number? value) (>= value gte))
               (some? lt)  (and (number? value) (< value lt))
               (some? lte) (and (number? value) (<= value lte))
               (some? contains)    (and (string? value) (str/includes? value contains))
               (some? starts_with) (and (string? value) (str/starts-with? value starts_with))
               (some? ends_with)   (and (string? value) (str/ends-with? value ends_with))
               (some? in)          (contains? (set in) value)
               :else true)))))
     filter-map)))

(defn- select-fields
  "Select specific fields from a row if fields list is provided."
  [row fields]
  (if (empty? fields)
    row
    (select-keys row (map keyword fields))))

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
   #{:basic-aggregations
     :native-parameters}
   feature))

(defmethod driver/mbql->native :rill
  [_ {database-id :database
      {source-table-id :source-table} :query}]
  (let [table (driver-api/table (driver-api/metadata-provider) source-table-id)
        table-name (:name table)]
    ;; Return JSON format for consistency
    {:query (json/generate-string {:api table-name})
     :database-id database-id}))

(defmethod driver/execute-reducible-query :rill
  [_ {native-query :native} _ respond]
  (let [database (driver-api/database (driver-api/metadata-provider))
        query-str (:query native-query)
        {:keys [api params filter fields limit]} (parse-query query-str)
        instance (get-instance database)
        url (api-url database "/v1/instances/" instance "/api/" api)
        response (fetch-json url params)
        ;; Handle both array responses and wrapped responses
        raw-data (cond
                   (sequential? response) response
                   (map? response) (or (:data response) [response])
                   :else [])
        ;; Apply client-side filtering
        filtered-data (if filter
                        (filterv #(matches-filter? % filter) raw-data)
                        raw-data)
        ;; Apply field selection
        selected-data (if fields
                        (mapv #(select-fields % fields) filtered-data)
                        filtered-data)
        ;; Apply limit
        limited-data (if limit
                       (vec (take limit selected-data))
                       selected-data)
        ;; Get column names from first row
        first-row (first limited-data)
        columns (when (map? first-row) (keys first-row))
        col-names (mapv name columns)
        ;; Build rows as vectors in column order
        rows (mapv (fn [row]
                     (mapv #(get row %) columns))
                   limited-data)]
    (respond
     {:cols (mapv (fn [col-name]
                    {:name col-name
                     :base_type :type/Text})
                  col-names)}
     rows)))
