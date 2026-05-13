(ns logseq.db.sqlite.linkml-import-test
  "Tests for the LinkML YAML schema -> sqlite-build EDN converter.

   Uses the actual LinkML tutorial fixtures as test inputs so coverage
   tracks real-world LinkML usage rather than synthetic shapes. After
   building the EDN we materialize it into a real datascript graph and
   then run the LinkML exporter over the result, verifying the
   round-trip preserves class shape + refinement constraints."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [logseq.db.frontend.linkml-export :as linkml-export]
            [logseq.db.sqlite.build :as sqlite-build]
            [logseq.db.sqlite.linkml-import :as linkml-import]
            [logseq.db.test.helper :as db-test]))

;; --------------------------------------------------------------------
;; Fixtures: parsed forms of real LinkML tutorial schemas. Mirrors what
;; js-yaml.load would produce — string keys throughout.
;; --------------------------------------------------------------------

(def ^:private tutorial03
  "Simplest tutorial: Person with identifier + required + pattern + range
   integer + min/max + multivalued."
  {"id" "https://w3id.org/linkml/examples/personinfo"
   "name" "personinfo"
   "default_range" "string"
   "classes"
   {"Person"
    {"attributes"
     {"id"        {"identifier" true}
      "full_name" {"required" true}
      "aliases"   {"multivalued" true}
      "phone"     {"pattern" "^[\\d\\(\\)\\-]+$"}
      "age"       {"range" "integer"
                   "minimum_value" 0
                   "maximum_value" 200}}}
    "Container"
    {"tree_root" true
     "attributes" {"persons" {"multivalued" true
                              "inlined_as_list" true
                              "range" "Person"}}}}})

(def ^:private tutorial07
  "Complex tutorial: inheritance + mixin + abstract."
  {"id" "https://w3id.org/linkml/examples/personinfo"
   "name" "personinfo"
   "classes"
   {"NamedThing"
    {"abstract" true
     "attributes" {"id"        {"identifier" true}
                   "full_name" {"description" "the canonical name of the entity"}}}
    "HasAliases"
    {"mixin" true
     "attributes" {"aliases" {"multivalued" true}}}
    "Person"
    {"is_a" "NamedThing"
     "mixins" ["HasAliases"]
     "attributes" {"phone" {"pattern" "^[\\d\\(\\)\\-]+$"}
                   "age"   {"range" "integer"
                            "minimum_value" 0
                            "maximum_value" 200}}}}})

;; --------------------------------------------------------------------
;; Pure-fn conversion tests (no datascript).
;; --------------------------------------------------------------------

(deftest tutorial03-converts-to-sqlite-build-edn
  (let [edn (linkml-import/schema->sqlite-build-edn tutorial03)
        cls (:classes edn)
        props (:properties edn)]
    (testing "every LinkML class becomes a Logseq class extending :logseq.class/Schema"
      (is (= 2 (count cls)))
      (is (contains? cls :Person))
      (is (contains? cls :Container))
      (doseq [[_ c] cls]
        (is (some #{:logseq.class/Schema} (:build/class-extends c)))))
    (testing "Person carries the inline-attribute slots"
      (is (= #{:user.property/id :user.property/full_name :user.property/aliases
               :user.property/phone :user.property/age}
             (set (:build/class-properties (:Person cls))))))
    (testing "refinement constraints carried per slot"
      (let [age (get props :user.property/age)
            phone (get props :user.property/phone)
            aliases (get props :user.property/aliases)
            full (get props :user.property/full_name)]
        (is (= :number (:logseq.property/type age)))
        (is (= "int" (get-in age [:build/properties :logseq.property.refinement/numeric-kind])))
        (is (= 0 (get-in age [:build/properties :logseq.property.refinement/min-value])))
        (is (= 200 (get-in age [:build/properties :logseq.property.refinement/max-value])))
        (is (= "^[\\d\\(\\)\\-]+$" (get-in phone [:build/properties :logseq.property.refinement/pattern])))
        (is (= :db.cardinality/many (:db/cardinality aliases)))
        (is (true? (get-in full [:build/properties :logseq.property.refinement/required?])))))))

(deftest tutorial07-handles-inheritance-and-mixins
  (let [edn (linkml-import/schema->sqlite-build-edn tutorial07)
        person (get-in edn [:classes :Person])
        extends (set (:build/class-extends person))]
    (testing "is_a + mixins both flatten into extends, plus the schema marker"
      (is (contains? extends :NamedThing))
      (is (contains? extends :HasAliases))
      (is (contains? extends :logseq.class/Schema)))
    (testing "description on a slot lands in refinement-description"
      (let [full (get-in edn [:properties :user.property/full_name])]
        (is (= "the canonical name of the entity"
               (get-in full [:build/properties :logseq.property.refinement/description])))))))

;; --------------------------------------------------------------------
;; End-to-end roundtrip: import a schema, materialize it, re-export.
;; --------------------------------------------------------------------

(deftest tutorial03-roundtrip-import-then-export
  (testing "tutorial03 imports, lands in datascript, and re-exports as valid LinkML YAML"
    (let [conn (db-test/create-conn)
          edn (linkml-import/schema->sqlite-build-edn tutorial03)
          _ (sqlite-build/create-blocks conn edn)
          yaml (linkml-export/build-linkml-schema @conn)]
      (is (string/includes? yaml "Person"))
      (is (string/includes? yaml "Container"))
      ;; Range integer survives from import → export
      (is (string/includes? yaml "range: integer"))
      ;; Refinements survive from LinkML → Logseq → LinkML
      (is (string/includes? yaml "minimum_value: 0"))
      (is (string/includes? yaml "maximum_value: 200"))
      (is (string/includes? yaml "pattern:")))))
