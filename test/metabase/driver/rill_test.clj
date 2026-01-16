(ns metabase.driver.rill-test
  "Unit tests for the Rill driver."
  (:require
   [clojure.test :refer :all]
   [metabase.driver.rill :as rill]))

;;; ---------------------------------------- Type Inference Tests ----------------------------------------

(deftest infer-base-type-test
  (testing "infer-base-type correctly identifies types"
    (testing "nil values"
      (is (= :type/Text (#'rill/infer-base-type nil))))

    (testing "string values"
      (is (= :type/Text (#'rill/infer-base-type "hello")))
      (is (= :type/Text (#'rill/infer-base-type "")))
      (is (= :type/Text (#'rill/infer-base-type "123"))))

    (testing "boolean values"
      (is (= :type/Boolean (#'rill/infer-base-type true)))
      (is (= :type/Boolean (#'rill/infer-base-type false))))

    (testing "integer values"
      (is (= :type/Integer (#'rill/infer-base-type 0)))
      (is (= :type/Integer (#'rill/infer-base-type 42)))
      (is (= :type/Integer (#'rill/infer-base-type -100))))

    (testing "float values"
      (is (= :type/Float (#'rill/infer-base-type 3.14)))
      (is (= :type/Float (#'rill/infer-base-type -0.5)))
      (is (= :type/Float (#'rill/infer-base-type 1.0))))

    (testing "other values default to Text"
      (is (= :type/Text (#'rill/infer-base-type {:nested "map"})))
      (is (= :type/Text (#'rill/infer-base-type [1 2 3]))))))

(deftest infer-fields-from-row-test
  (testing "infer-fields-from-row extracts field definitions"
    (testing "simple row with mixed types"
      (let [row {:name "John" :age 30 :active true :score 95.5}
            fields (#'rill/infer-fields-from-row row)]
        (is (set? fields))
        (is (= 4 (count fields)))
        (is (contains? fields {:name "name" :database-type "json" :base-type :type/Text}))
        (is (contains? fields {:name "age" :database-type "json" :base-type :type/Integer}))
        (is (contains? fields {:name "active" :database-type "json" :base-type :type/Boolean}))
        (is (contains? fields {:name "score" :database-type "json" :base-type :type/Float}))))

    (testing "empty row"
      (is (= #{} (#'rill/infer-fields-from-row {}))))

    (testing "non-map input returns nil"
      (is (nil? (#'rill/infer-fields-from-row nil)))
      (is (nil? (#'rill/infer-fields-from-row [1 2 3])))
      (is (nil? (#'rill/infer-fields-from-row "string"))))))

;;; ---------------------------------------- Query Parsing Tests ----------------------------------------

(deftest parse-query-test
  (testing "parse-query handles various query formats"
    (testing "plain API name string"
      (let [result (#'rill/parse-query "my_api")]
        (is (= "my_api" (:api result)))
        (is (nil? (:params result)))
        (is (nil? (:filter result)))
        (is (nil? (:fields result)))
        (is (nil? (:limit result)))))

    (testing "plain API name with whitespace"
      (let [result (#'rill/parse-query "  metrics_api  ")]
        (is (= "metrics_api" (:api result)))))

    (testing "JSON query with api only"
      (let [result (#'rill/parse-query "{\"api\": \"sales_api\"}")]
        (is (= "sales_api" (:api result)))
        (is (nil? (:params result)))))

    (testing "JSON query with all options"
      (let [result (#'rill/parse-query "{\"api\": \"metrics\", \"params\": {\"year\": 2024}, \"filter\": {\"status\": \"active\"}, \"fields\": [\"name\", \"value\"], \"limit\": 100}")]
        (is (= "metrics" (:api result)))
        (is (= {:year 2024} (:params result)))
        (is (= {:status "active"} (:filter result)))
        (is (= ["name" "value"] (:fields result)))
        (is (= 100 (:limit result)))))

    (testing "JSON query with alternative keys (table, endpoint)"
      (let [result1 (#'rill/parse-query "{\"table\": \"my_table\"}")
            result2 (#'rill/parse-query "{\"endpoint\": \"my_endpoint\"}")]
        (is (= "my_table" (:api result1)))
        (is (= "my_endpoint" (:api result2)))))

    (testing "empty query string"
      (let [result (#'rill/parse-query "")]
        (is (= "" (:api result)))))

    (testing "nil query string"
      (let [result (#'rill/parse-query nil)]
        (is (= "" (:api result)))))))

;;; ---------------------------------------- Filter Matching Tests ----------------------------------------

(deftest matches-filter-test
  (testing "matches-filter? correctly filters rows"
    (let [row {:name "Alice" :age 30 :status "active" :score 85.5}]

      (testing "empty filter matches everything"
        (is (true? (#'rill/matches-filter? row {})))
        (is (true? (#'rill/matches-filter? row nil))))

      (testing "simple equality filter"
        (is (true? (#'rill/matches-filter? row {:status "active"})))
        (is (false? (#'rill/matches-filter? row {:status "inactive"})))
        (is (true? (#'rill/matches-filter? row {:age 30})))
        (is (false? (#'rill/matches-filter? row {:age 25}))))

      (testing "multiple equality conditions (AND)"
        (is (true? (#'rill/matches-filter? row {:status "active" :age 30})))
        (is (false? (#'rill/matches-filter? row {:status "active" :age 25}))))

      (testing "eq operator"
        (is (true? (#'rill/matches-filter? row {:name {:eq "Alice"}})))
        (is (false? (#'rill/matches-filter? row {:name {:eq "Bob"}}))))

      (testing "ne operator"
        (is (true? (#'rill/matches-filter? row {:name {:ne "Bob"}})))
        (is (false? (#'rill/matches-filter? row {:name {:ne "Alice"}}))))

      (testing "gt operator"
        (is (true? (#'rill/matches-filter? row {:age {:gt 25}})))
        (is (false? (#'rill/matches-filter? row {:age {:gt 30}})))
        (is (false? (#'rill/matches-filter? row {:age {:gt 35}}))))

      (testing "gte operator"
        (is (true? (#'rill/matches-filter? row {:age {:gte 30}})))
        (is (true? (#'rill/matches-filter? row {:age {:gte 25}})))
        (is (false? (#'rill/matches-filter? row {:age {:gte 35}}))))

      (testing "lt operator"
        (is (true? (#'rill/matches-filter? row {:age {:lt 35}})))
        (is (false? (#'rill/matches-filter? row {:age {:lt 30}})))
        (is (false? (#'rill/matches-filter? row {:age {:lt 25}}))))

      (testing "lte operator"
        (is (true? (#'rill/matches-filter? row {:age {:lte 30}})))
        (is (true? (#'rill/matches-filter? row {:age {:lte 35}})))
        (is (false? (#'rill/matches-filter? row {:age {:lte 25}}))))

      (testing "contains operator"
        (is (true? (#'rill/matches-filter? row {:name {:contains "lic"}})))
        (is (true? (#'rill/matches-filter? row {:name {:contains "Alice"}})))
        (is (false? (#'rill/matches-filter? row {:name {:contains "xyz"}}))))

      (testing "starts_with operator"
        (is (true? (#'rill/matches-filter? row {:name {:starts_with "Ali"}})))
        (is (true? (#'rill/matches-filter? row {:name {:starts_with "A"}})))
        (is (false? (#'rill/matches-filter? row {:name {:starts_with "B"}}))))

      (testing "ends_with operator"
        (is (true? (#'rill/matches-filter? row {:name {:ends_with "ice"}})))
        (is (true? (#'rill/matches-filter? row {:name {:ends_with "e"}})))
        (is (false? (#'rill/matches-filter? row {:name {:ends_with "a"}}))))

      (testing "in operator"
        (is (true? (#'rill/matches-filter? row {:status {:in ["active" "pending"]}})))
        (is (true? (#'rill/matches-filter? row {:age {:in [30 25 35]}})))
        (is (false? (#'rill/matches-filter? row {:status {:in ["inactive" "deleted"]}}))))

      (testing "numeric operators on non-numeric fields"
        ;; Should return false when comparing non-numeric values
        (is (false? (#'rill/matches-filter? row {:name {:gt 10}})))
        (is (false? (#'rill/matches-filter? row {:name {:gte 10}})))
        (is (false? (#'rill/matches-filter? row {:name {:lt 10}})))
        (is (false? (#'rill/matches-filter? row {:name {:lte 10}}))))

      (testing "string operators on non-string fields"
        ;; Should return false when using string ops on non-strings
        (is (false? (#'rill/matches-filter? row {:age {:contains "3"}})))
        (is (false? (#'rill/matches-filter? row {:age {:starts_with "3"}})))
        (is (false? (#'rill/matches-filter? row {:age {:ends_with "0"}})))))))

;;; ---------------------------------------- Field Selection Tests ----------------------------------------

(deftest select-fields-test
  (testing "select-fields correctly selects fields"
    (let [row {:name "Alice" :age 30 :status "active" :score 85.5}]

      (testing "empty fields returns full row"
        (is (= row (#'rill/select-fields row [])))
        (is (= row (#'rill/select-fields row nil))))

      (testing "select specific fields"
        (is (= {:name "Alice" :age 30}
               (#'rill/select-fields row ["name" "age"]))))

      (testing "select single field"
        (is (= {:name "Alice"}
               (#'rill/select-fields row ["name"]))))

      (testing "select non-existent field"
        (is (= {}
               (#'rill/select-fields row ["nonexistent"]))))

      (testing "select mix of existent and non-existent fields"
        (is (= {:name "Alice"}
               (#'rill/select-fields row ["name" "nonexistent"])))))))

;;; ---------------------------------------- URL Building Tests ----------------------------------------

(deftest get-host-test
  (testing "get-host removes trailing slashes"
    (let [db1 {:details {:host "http://localhost:9009"}}
          db2 {:details {:host "http://localhost:9009/"}}
          db3 {:details {:host "http://localhost:9009//"}}]
      (is (= "http://localhost:9009" (#'rill/get-host db1)))
      (is (= "http://localhost:9009" (#'rill/get-host db2)))
      (is (= "http://localhost:9009/" (#'rill/get-host db3)))))) ; only removes one trailing slash

(deftest get-instance-test
  (testing "get-instance returns instance or default"
    (is (= "my-instance"
           (#'rill/get-instance {:details {:instance "my-instance"}})))
    (is (= "default"
           (#'rill/get-instance {:details {}})))
    (is (= "default"
           (#'rill/get-instance {:details {:instance nil}})))))

(deftest api-url-test
  (testing "api-url builds correct URLs"
    (let [database {:details {:host "http://localhost:9009"}}]
      (is (= "http://localhost:9009/v1/instances/default"
             (#'rill/api-url database "/v1/instances/" "default")))
      (is (= "http://localhost:9009/v1/instances/default/api/my_api"
             (#'rill/api-url database "/v1/instances/" "default" "/api/" "my_api"))))))

;;; ---------------------------------------- Integration Test Helpers ----------------------------------------

(deftest ^:integration can-connect-test
  (testing "can-connect? returns false for invalid host"
    ;; This test doesn't require a running Rill server
    (is (false? (#'rill/can-connect? :rill {:host "http://localhost:99999"
                                             :instance "default"})))))

;;; ---------------------------------------- Run All Tests ----------------------------------------

(defn run-tests []
  (run-tests 'metabase.driver.rill-test))
