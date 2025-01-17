(ns metabase.query-processor.middleware.resolve-referenced-test
  (:require
   [clojure.test :refer :all]
   [metabase.models.card :refer [Card]]
   [metabase.models.database :refer [Database]]
   [metabase.query-processor.middleware.parameters-test
    :refer [card-template-tags]]
   [metabase.query-processor.middleware.resolve-referenced
    :as qp.resolve-referenced]
   [metabase.query-processor.store :as qp.store]
   [metabase.test :as mt]
   [toucan2.core :as t2]
   [toucan2.tools.with-temp :as t2.with-temp])
  (:import
   (clojure.lang ExceptionInfo)))

(set! *warn-on-reflection* true)

(deftest resolve-card-resources-test
  (testing "resolve stores source table from referenced card"
    (t2.with-temp/with-temp [Card mbql-card {:dataset_query (mt/mbql-query venues
                                                              {:filter [:< $price 3]})}]
      (let [query {:database (mt/id)
                   :native   {:template-tags
                              {"tag-name-not-important1" {:type    :card
                                                          :card-id (:id mbql-card)}}}}]
        (qp.store/with-metadata-provider (mt/id)
          (is (= query
                 (#'qp.resolve-referenced/resolve-referenced-card-resources* query)))
          (is (some? (qp.store/table (mt/id :venues))))
          (is (some? (qp.store/field (mt/id :venues :price)))))))))

(deftest referenced-query-from-different-db-test
  (testing "fails on query that references a native query from a different database"
    (mt/with-temp [Database outer-query-db {}
                   Card     card {:dataset_query
                                  {:database (mt/id)
                                   :type     :native
                                   :native   {:query "SELECT 1 AS \"foo\", 2 AS \"bar\", 3 AS \"baz\""}}}]
      (let [card-id     (:id card)
            card-query  (:dataset_query card)
            tag-name    (str "#" card-id)
            query-db-id (:id outer-query-db)
            query       {:database query-db-id ; Note db-1 is used here
                         :type     :native
                         :native   {:query         (format "SELECT * FROM {{%s}} AS x" tag-name)
                                    :template-tags {tag-name ; This tag's query is from the test db
                                                    {:id tag-name, :name tag-name, :display-name tag-name,
                                                     :type "card", :card-id card-id}}}}]
        (is (= {:referenced-query     card-query
                :expected-database-id query-db-id}
               (try
                 (#'qp.resolve-referenced/check-query-database-id= card-query query-db-id)
                 (catch ExceptionInfo exc
                   (ex-data exc)))))

        (is (nil? (#'qp.resolve-referenced/check-query-database-id= card-query (mt/id))))
        (qp.store/with-metadata-provider (mt/id)
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"\QReferenced query is from a different database\E"
               (#'qp.resolve-referenced/resolve-referenced-card-resources* query)))
          (is (= {:referenced-query     card-query
                  :expected-database-id query-db-id}
                 (try
                   (#'qp.resolve-referenced/resolve-referenced-card-resources* query)
                   (catch ExceptionInfo exc
                     (ex-data exc))))))))))

(deftest referenced-query-from-different-db-test-2
  (testing "fails on query that references an MBQL query from a different database"
    (mt/with-temp [Database outer-query-db {}
                   Card     card {:dataset_query (mt/mbql-query venues
                                                                {:filter [:< $price 3]})}]
      (let [card-id     (:id card)
            card-query  (:dataset_query card)
            tag-name    (str "#" card-id)
            query-db-id (:id outer-query-db)
            query       {:database query-db-id ; Note outer-query-db is used here
                         :type     :native
                         :native   {:query         (format "SELECT * FROM {{%s}} AS x" tag-name)
                                    :template-tags {tag-name ; This tag's query is from the test db
                                                    {:id tag-name, :name tag-name, :display-name tag-name,
                                                     :type "card", :card-id card-id}}}}]
        (is (= {:referenced-query     card-query
                :expected-database-id query-db-id}
               (try
                 (#'qp.resolve-referenced/check-query-database-id= card-query query-db-id)
                 (catch ExceptionInfo exc
                   (ex-data exc)))))
        (is (nil? (#'qp.resolve-referenced/check-query-database-id= card-query (mt/id))))
        (qp.store/with-metadata-provider (mt/id)
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"\QReferenced query is from a different database\E"
               (#'qp.resolve-referenced/resolve-referenced-card-resources* query)))
          (is (= {:referenced-query     card-query
                  :expected-database-id query-db-id}
                 (try
                   (#'qp.resolve-referenced/resolve-referenced-card-resources* query)
                   (catch ExceptionInfo exc
                     (ex-data exc))))))))))

(deftest circular-referencing-tags-test
  (testing "fails on query with circular referencing sub-queries"
    (mt/with-temp [Card card-1 {:dataset_query (mt/native-query {:query "SELECT 1"})}
                   Card card-2 {:dataset_query (mt/native-query
                                                 {:query         (str "SELECT * FROM {{#" (:id card-1) "}} AS c1")
                                                  :template-tags (card-template-tags [(:id card-1)])})}]
      ;; Setup circular reference from card-1 to card-2 (card-2 already references card-1)
      (let [card-1-id  (:id card-1)]
        (t2/update! Card (:id card-1) {:dataset_query (mt/native-query
                                                        {:query         (str "SELECT * FROM {{#" (:id card-2) "}} AS c2")
                                                         :template-tags (card-template-tags [(:id card-2)])})})
        (let [entrypoint-query (mt/native-query
                                 {:query (str "SELECT * FROM {{#" (:id card-1) "}}")
                                  :template-tags (card-template-tags [card-1-id])})]
          (qp.store/with-metadata-provider (mt/id)
            (is (= (#'qp.resolve-referenced/circular-ref-error (:id card-2) card-1-id)
                   (try
                     (#'qp.resolve-referenced/check-for-circular-references! entrypoint-query)
                     (catch ExceptionInfo e
                       (.getMessage e)))))))))))
