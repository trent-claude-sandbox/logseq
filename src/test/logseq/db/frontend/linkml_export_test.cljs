(ns logseq.db.frontend.linkml-export-test
  "Tests for the LinkML schema exporter. Builds a small fixture graph with
   one #Schema-extending class and one unrelated class, then asserts that
   the emitted YAML covers the schema-graded class and excludes the other.
   Refinement constraints round-trip into the slot definitions."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [logseq.db.frontend.linkml-export :as linkml-export]
            [logseq.db.test.helper :as db-test]))

;; Tiny graph: a SchemaGradedThing extends :Schema, has a `title` and a
;; `count` property with refinements. UnrelatedThing has its own
;; property but does NOT extend Schema, so it must not appear.
(defn- build-conn []
  (db-test/create-conn-with-blocks
   {:properties {:user.property/title
                 {:logseq.property/type :default
                  :build/properties {:logseq.property.refinement/min-length 1
                                     :logseq.property.refinement/max-length 200
                                     :logseq.property.refinement/required? true}}
                 :user.property/count
                 {:logseq.property/type :number
                  :build/properties {:logseq.property.refinement/min-value 0
                                     :logseq.property.refinement/max-value 100
                                     :logseq.property.refinement/numeric-kind "int"}}
                 :user.property/ignored
                 {:logseq.property/type :default}}
    :classes {:SchemaGradedThing
              {:block/title "SchemaGradedThing"
               :build/class-extends [:logseq.class/Schema]
               :build/class-properties [:user.property/title :user.property/count]}
              :UnrelatedThing
              {:block/title "UnrelatedThing"
               :build/class-properties [:user.property/ignored]}}}))

(deftest schema-graded-classes-only
  (testing "Only classes that extend #Schema show up in the LinkML output"
    (let [conn (build-conn)
          yaml (linkml-export/build-linkml-schema @conn)]
      (is (string/includes? yaml "SchemaGradedThing"))
      (is (not (string/includes? yaml "UnrelatedThing"))))))

(deftest refinements-emit-as-slot-constraints
  (testing "min/max length fold into a pattern; numeric-kind sharpens range"
    (let [yaml (linkml-export/build-linkml-schema @(build-conn))]
      ;; Number property gets `range: integer` from numeric-kind = int
      (is (string/includes? yaml "range: integer"))
      ;; Number ranges flow through directly
      (is (string/includes? yaml "minimum_value: 0"))
      (is (string/includes? yaml "maximum_value: 100"))
      ;; Text length collapses into a regex pattern of `^.{1,200}$`
      (is (string/includes? yaml "^.{1,200}$"))
      ;; Required slot flag survives
      (is (string/includes? yaml "required: true")))))

(deftest empty-graph-emits-empty-schema
  (testing "A graph with no schema-graded classes still emits a valid schema skeleton"
    (let [conn (db-test/create-conn-with-blocks
                {:classes {:JustAClass {:block/title "JustAClass"}}})
          yaml (linkml-export/build-linkml-schema @conn)]
      ;; Header is always present.
      (is (string/includes? yaml "id: "))
      (is (string/includes? yaml "name: "))
      ;; But no user class shows up.
      (is (not (string/includes? yaml "JustAClass"))))))

(deftest er-diagram-emits-mermaid-with-schema-graded-classes
  (testing "build-er-diagram produces a Mermaid erDiagram covering the seeded classes"
    (let [yaml (linkml-export/build-er-diagram @(build-conn))]
      (is (string/starts-with? yaml "erDiagram"))
      (is (string/includes? yaml "SchemaGradedThing"))
      (is (not (string/includes? yaml "UnrelatedThing"))))))

(deftest pattern-combines-with-length
  (testing "When both a regex pattern AND length bounds are set, the emitted pattern wraps both via lookahead"
    (let [conn (db-test/create-conn-with-blocks
                {:properties {:user.property/code
                              {:logseq.property/type :default
                               :build/properties {:logseq.property.refinement/pattern "^[A-Z]+$"
                                                  :logseq.property.refinement/min-length 2
                                                  :logseq.property.refinement/max-length 5}}}
                 :classes {:Thing
                           {:block/title "Thing"
                            :build/class-extends [:logseq.class/Schema]
                            :build/class-properties [:user.property/code]}}})
          yaml (linkml-export/build-linkml-schema @conn)]
      (is (string/includes? yaml "(?=^.{2,5}$)^[A-Z]+$")))))
