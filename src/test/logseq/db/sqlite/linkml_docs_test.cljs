(ns logseq.db.sqlite.linkml-docs-test
  "Spot-checks the LinkML doc-pages seed."
  (:require [cljs.test :refer [deftest is testing]]
            [datascript.core :as d]
            [logseq.db.sqlite.demo-content :as demo-content]
            [logseq.db.sqlite.linkml-docs :as linkml-docs]
            [logseq.db.test.helper :as db-test]))

(defn- seeded-conn []
  (let [conn (db-test/create-conn)]
    ;; Demo seeds the :user.class/LinkmlDoc parent (no — linkml-docs does).
    ;; Just calling linkml-docs/seed! works once :logseq.class/Schema is in
    ;; place via the create-conn defaults.
    (demo-content/seed! conn)
    (linkml-docs/seed! conn)
    conn))

(deftest seed-creates-doc-class-and-pages
  (let [db @(seeded-conn)
        doc-class (d/entity db :user.class/LinkmlDoc)
        overview (->> (d/datoms db :avet :block/title "LinkML: overview")
                      first :e (d/entity db))
        impl-page (->> (d/datoms db :avet :block/title "LinkML: implements vs instantiates vs is_a vs mixins")
                       first :e (d/entity db))
        validate-page (->> (d/datoms db :avet :block/title "LinkML: validation in this sketchpad")
                           first :e (d/entity db))]
    (testing "the linkml/doc class lands as a built-in"
      (is (some? doc-class))
      (is (= "linkml/doc" (:block/title doc-class))))
    (testing "key doc pages were created"
      (is (some? overview))
      (is (some? impl-page))
      (is (some? validate-page)))))

(deftest seed-is-idempotent
  (let [conn (db-test/create-conn)]
    (demo-content/seed! conn)
    (linkml-docs/seed! conn)
    (let [before-count (count (d/datoms @conn :eavt))]
      (linkml-docs/seed! conn)
      (is (= before-count (count (d/datoms @conn :eavt)))))))
