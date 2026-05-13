(ns logseq.db.sqlite.schema-block-parser-test
  "Tests the nested-block schema authoring parser. Each test feeds the
   parser a Clojure-data block tree (the same shape the materialize
   handler builds from datascript) and asserts the resulting
   sqlite-build EDN is what create-blocks will turn into the intended
   classes + properties."
  (:require [cljs.test :refer [deftest is testing]]
            [clojure.string :as string]
            [logseq.db.frontend.linkml-export :as linkml-export]
            [logseq.db.sqlite.build :as sqlite-build]
            [logseq.db.sqlite.schema-block-parser :as p]
            [logseq.db.test.helper :as db-test]))

(defn- doc
  "Build a doc tree from a vector-of-classes shorthand."
  [classes]
  {:title "schema-doc"
   :children classes})

(defn- cls
  ([name children] {:title (str name " #Class") :children children})
  ([name] (cls name [])))

(defn- kv [k v] {:title (str k ": " v)})

(defn- slot
  [n children]
  {:title (str n) :children children})

;; -- Smallest possible class -------------------------------------------

(deftest parses-bare-class
  (testing "a class with no slots, no metadata"
    (let [out (p/parse-schema-doc (doc [(cls "Book")]))]
      (is (= #{:Book} (set (keys (:classes out)))))
      (is (= [:logseq.class/Schema]
             (-> out :classes :Book :build/class-extends)))
      (is (empty? (-> out :classes :Book :build/class-properties))))))

;; -- Metadata pickup ---------------------------------------------------

(deftest extends-and-description-on-class
  (let [out (p/parse-schema-doc
             (doc [(cls "Book"
                        [(kv "extends" "Item")
                         (kv "description" "A printed text artifact")])]))
        book (-> out :classes :Book)]
    (testing "extends prepended with the schema marker"
      (is (= [:logseq.class/Schema :Item] (:build/class-extends book))))
    (testing "description lands on :build/properties"
      (is (= "A printed text artifact"
             (get-in book [:build/properties :logseq.property/description]))))))

(deftest is_a-and-mixins-aliases
  (let [out (p/parse-schema-doc
             (doc [(cls "Person"
                        [(kv "is_a" "NamedThing")
                         (kv "mixins" "HasAliases, Timestamped")])]))]
    (is (= #{:logseq.class/Schema :NamedThing :HasAliases :Timestamped}
           (set (-> out :classes :Person :build/class-extends))))))

;; -- Slot parsing ------------------------------------------------------

(deftest slot-types-and-refinements
  (let [out (p/parse-schema-doc
             (doc [(cls "Book"
                        [{:title "slots:"
                          :children
                          [(slot "title" [(kv "type" "string")
                                          (kv "required" "true")
                                          (kv "min-length" "1")
                                          (kv "max-length" "200")])
                           (slot "page-count" [(kv "type" "number")
                                               (kv "numeric-kind" "int")
                                               (kv "min-value" "1")
                                               (kv "max-value" "50000")])
                           (slot "tags" [(kv "type" "string")
                                         (kv "cardinality" "many")])
                           (slot "url" [(kv "type" "url")
                                        (kv "pattern" "^https?://")])]}])]))
        props (:properties out)]
    (testing "all four slots register"
      (is (= #{:user.property/title :user.property/page-count
               :user.property/tags :user.property/url}
             (set (keys props)))))
    (testing "title carries length + required refinements"
      (let [title (:user.property/title props)]
        (is (= :default (:logseq.property/type title)))
        (is (true? (get-in title [:build/properties :logseq.property.refinement/required?])))
        (is (= 1 (get-in title [:build/properties :logseq.property.refinement/min-length])))
        (is (= 200 (get-in title [:build/properties :logseq.property.refinement/max-length])))))
    (testing "page-count is number + int + range"
      (let [pc (:user.property/page-count props)]
        (is (= :number (:logseq.property/type pc)))
        (is (= "int" (get-in pc [:build/properties :logseq.property.refinement/numeric-kind])))
        (is (= 1 (get-in pc [:build/properties :logseq.property.refinement/min-value])))
        (is (= 50000 (get-in pc [:build/properties :logseq.property.refinement/max-value])))))
    (testing "cardinality: many becomes :db.cardinality/many"
      (is (= :db.cardinality/many (:db/cardinality (:user.property/tags props)))))
    (testing "url type + pattern"
      (let [u (:user.property/url props)]
        (is (= :url (:logseq.property/type u)))
        (is (= "^https?://" (get-in u [:build/properties :logseq.property.refinement/pattern])))))))

;; -- Class -> slots wiring --------------------------------------------

(deftest class-references-its-slots
  (let [out (p/parse-schema-doc
             (doc [(cls "Book"
                        [{:title "slots:"
                          :children [(slot "title" [(kv "type" "string")])
                                     (slot "author" [(kv "type" "string")])]}])]))]
    (is (= [:user.property/title :user.property/author]
           (-> out :classes :Book :build/class-properties)))))

;; -- Skip non-class content -------------------------------------------

(deftest materialize-roundtrip-yields-linkml-yaml
  (testing "nested-block schema-doc -> parser -> create-blocks -> LinkML export round-trips"
    (let [tree (doc [(cls "Book"
                          [(kv "extends" "schema")
                           {:title "slots:"
                            :children [(slot "title" [(kv "type" "string")
                                                      (kv "required" "true")
                                                      (kv "min-length" "1")
                                                      (kv "max-length" "200")])
                                       (slot "page-count" [(kv "type" "number")
                                                           (kv "numeric-kind" "int")
                                                           (kv "min-value" "1")
                                                           (kv "max-value" "50000")])]}])])
          edn (p/parse-schema-doc tree)
          conn (db-test/create-conn)
          _ (sqlite-build/create-blocks conn edn)
          yaml (linkml-export/build-linkml-schema @conn)]
      (testing "Book class shows up in LinkML output"
        (is (string/includes? yaml "Book")))
      (testing "page-count's integer range survives the round-trip"
        (is (string/includes? yaml "range: integer"))
        (is (string/includes? yaml "minimum_value: 1"))
        (is (string/includes? yaml "maximum_value: 50000")))
      (testing "title's length constraints fold into the LinkML pattern"
        (is (string/includes? yaml "^.{1,200}$"))))))

(deftest commentary-blocks-do-not-show-up-as-classes
  (let [out (p/parse-schema-doc
             {:title "schema-doc"
              :children [{:title "Some commentary that's not a class :"}
                         (cls "Book")]})]
    (is (= #{:Book} (set (keys (:classes out)))))))
