(ns logseq.db.sqlite.demo-content-test
  "Tests the demo-graph seed (sqlite/demo_content) materializes a complete
   reading-queue domain into a fresh DB graph, and that the LinkML exporter
   picks up the Schema-graded classes from it."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [datascript.core :as d]
            [logseq.db.frontend.class :as db-class]
            [logseq.db.frontend.linkml-export :as linkml-export]
            [logseq.db.sqlite.demo-content :as demo-content]
            [logseq.db.test.helper :as db-test]))

(defn- seeded-conn []
  (let [conn (db-test/create-conn)]
    (demo-content/seed! conn)
    conn))

(deftest seed-creates-schema-graded-classes
  (let [db @(seeded-conn)
        item    (d/entity db :user.class/Item)
        book    (d/entity db :user.class/Book)
        article (d/entity db :user.class/Article)
        inprog  (d/entity db :user.class/InProgress)]
    (testing "all four demo classes exist"
      (is (some? item))
      (is (some? book))
      (is (some? article))
      (is (some? inprog)))
    (testing "Item + InProgress extend Schema directly, Book + Article via Item"
      (is (db-class/schema-graded? item))
      (is (db-class/schema-graded? book))
      (is (db-class/schema-graded? article))
      (is (db-class/schema-graded? inprog)))))

(deftest seed-creates-refinement-bearing-properties
  (let [db @(seeded-conn)
        title  (d/entity db :user.property/title)
        page   (d/entity db :user.property/page-count)
        url    (d/entity db :user.property/url)
        frac   (d/entity db :user.property/fraction-done)]
    (testing "title has min-length, max-length, required"
      (is (= 1   (:logseq.property.refinement/min-length title)))
      (is (= 200 (:logseq.property.refinement/max-length title)))
      (is (true? (:logseq.property.refinement/required? title))))
    (testing "page-count has min/max-value and numeric-kind int"
      (is (= 1     (:logseq.property.refinement/min-value page)))
      (is (= 50000 (:logseq.property.refinement/max-value page)))
      (is (= "int" (:logseq.property.refinement/numeric-kind page))))
    (testing "url has the http(s) regex pattern"
      (is (= "^https?://" (:logseq.property.refinement/pattern url))))
    (testing "fraction-done uses float numeric-kind with 0..1 range"
      (is (= "float" (:logseq.property.refinement/numeric-kind frac)))
      (is (= 0 (:logseq.property.refinement/min-value frac)))
      (is (= 1 (:logseq.property.refinement/max-value frac))))))

(deftest seed-is-idempotent
  (let [conn (db-test/create-conn)
        _ (demo-content/seed! conn)
        before-count (count (d/datoms @conn :eavt))
        _ (demo-content/seed! conn)
        after-count (count (d/datoms @conn :eavt))]
    (is (= before-count after-count) "second seed must be a no-op")))

(deftest linkml-export-picks-up-demo-classes
  (let [yaml (linkml-export/build-linkml-schema @(seeded-conn))]
    (testing "all four demo classes show up in the YAML output"
      (is (string/includes? yaml "Item"))
      (is (string/includes? yaml "Book"))
      (is (string/includes? yaml "Article"))
      (is (string/includes? yaml "InProgress")))
    (testing "refinements survive into LinkML slot definitions"
      ;; Number ranges flow through directly
      (is (string/includes? yaml "minimum_value: 1"))
      (is (string/includes? yaml "maximum_value: 50000"))
      ;; Title's min-length/max-length collapse into a pattern
      (is (string/includes? yaml "^.{1,200}$"))
      ;; URL's pattern survives as-is
      (is (string/includes? yaml "^https?://"))
      ;; numeric-kind sharpens the range
      (is (string/includes? yaml "range: integer"))
      ;; required? propagates
      (is (string/includes? yaml "required: true")))))
