(ns frontend.handler.db-based.export
  "Handles DB graph exports and imports across graphs"
  (:require ["js-yaml" :as yaml]
            [cljs.pprint :as pprint]
            [clojure.string :as string]
            [frontend.context.i18n :refer [t]]
            [frontend.handler.notification :as notification]
            [frontend.state :as state]
            [frontend.util :as util]
            [frontend.util.page :as page-util]
            [goog.dom :as gdom]
            [logseq.db.sqlite.export :as sqlite-export]
            [promesa.core :as p]))

(defn- <export-edn-helper
  "Gets export-edn and validates export for smaller exports. Copied from component.export/<export-edn-helper"
  [export-args]
  (p/let [export-edn (state/<invoke-db-worker :thread-api/export-edn (state/get-current-repo) export-args)]
    (if-let [error (:error (sqlite-export/validate-export export-edn))]
      (do
        (js/console.log "Invalid export EDN:")
        (pprint/pprint export-edn)
        {:export-edn-error error})
      export-edn)))

(defn ^:export export-block-data []
  ;; Use editor state to locate most recent block
  (if-let [block-uuid (:block-id (first (state/get-editor-args)))]
    (p/let [result (state/<invoke-db-worker :thread-api/export-edn
                                            (state/get-current-repo)
                                            {:export-type :block :block-id [:block/uuid block-uuid]})
            pull-data (with-out-str (pprint/pprint result))]
      (when-not (:export-edn-error result)
        (.writeText js/navigator.clipboard pull-data)
        (println pull-data)
        (notification/show! (t :export/block-data-copied) :success)))
    (notification/show! (t :block/not-found-warning) :warning)))

(defn export-view-nodes-data [rows {:keys [group-by?]}]
  (p/let [result (<export-edn-helper {:export-type :view-nodes
                                      :rows rows
                                      :group-by? group-by?})
          pull-data (with-out-str (pprint/pprint result))]
    (if (:export-edn-error result)
      (notification/show! (:export-edn-error result) :error)
      (do (.writeText js/navigator.clipboard pull-data)
          (println pull-data)
          (notification/show! (t :export/view-nodes-data-copied) :success)))))

(defn ^:export export-page-data []
  (if-let [page-id (page-util/get-current-page-id)]
    (p/let [result (<export-edn-helper {:export-type :page :page-id page-id})
            pull-data (with-out-str (pprint/pprint result))]
      (if (:export-edn-error result)
        (notification/show! (:export-edn-error result) :error)
        (do (.writeText js/navigator.clipboard pull-data)
            (println pull-data)
            (notification/show! (t :export/page-data-copied) :success))))
    (notification/show! (t :page/not-found-warning) :warning)))

(defn ^:export export-graph-ontology-data []
  (p/let [result (state/<invoke-db-worker :thread-api/export-edn
                                          (state/get-current-repo)
                                          {:export-type :graph-ontology})
          pull-data (with-out-str (pprint/pprint result))]
    (when-not (:export-edn-error result)
      (.writeText js/navigator.clipboard pull-data)
      (println pull-data)
      (js/console.log (str "Exported " (count (:classes result)) " classes and "
                           (count (:properties result)) " properties"))
      (notification/show! (t :export/graph-ontology-data-copied) :success))))

;; Copied from handler.export
(defn- file-name [repo extension]
  (-> repo
      (string/replace #"^/+" "")
      (str "_" (quot (util/time-ms) 1000))
      (str "." (string/lower-case (name extension)))))

(defn export-repo-as-db-edn!
  "Run db validation before exporting EDN"
  [repo]
  (p/let [validate-result (state/<invoke-db-worker :thread-api/validate-db
                                                   (state/get-current-repo))
          {:keys [errors]} validate-result
          _ (when (seq errors)
              (throw (ex-info "Graph validation failed" validate-result)))
          result (state/<invoke-db-worker :thread-api/export-edn
                                          (state/get-current-repo)
                                          {:export-type :graph
                                           :graph-options {:include-timestamps? true}})
          pull-data (with-out-str (pprint/pprint result))]
    (when-not (:export-edn-error result)
      (let [data-str (some->> pull-data
                              js/encodeURIComponent
                              (str "data:text/edn;charset=utf-8,"))
            filename (file-name repo :edn)]
        (when-let [anchor (gdom/getElement "download-as-db-edn")]
          (.setAttribute anchor "href" data-str)
          (.setAttribute anchor "download" filename)
          (.click anchor))))))

(defn ^:export export-linkml-er-diagram
  "Walks every schema-graded class and emits a Mermaid `erDiagram`
   covering them. Mirrors LinkML's `gen-erdiagram` CLI. The Mermaid
   text is copied to the clipboard and downloaded as a `.mmd` file so
   it can be opened in any Mermaid renderer or pasted directly into a
   Logseq block (which renders Mermaid blocks inline)."
  []
  (p/let [result (state/<invoke-db-worker :thread-api/export-edn
                                          (state/get-current-repo)
                                          {:export-type :linkml-erdiagram})
          mermaid (:mermaid result)]
    (when mermaid
      (.writeText js/navigator.clipboard mermaid)
      (println mermaid)
      (let [repo (state/get-current-repo)
            data-str (str "data:text/plain;charset=utf-8,"
                          (js/encodeURIComponent mermaid))
            filename (file-name repo :mmd)]
        (when-let [anchor (gdom/getElement "download-as-db-edn")]
          (.setAttribute anchor "href" data-str)
          (.setAttribute anchor "download" filename)
          (.click anchor)))
      (notification/show! "Exported LinkML ER diagram (Mermaid) to clipboard + download." :success))))

(defn ^:export import-linkml-schema
  "Parse a LinkML YAML string and materialize every class/slot/enum in
   it as schema-graded Logseq nodes. Each imported class extends
   :logseq.class/Schema so the round-trip back through
   export-linkml-schema reconstructs the same LinkML output (subject to
   the feature subset documented in `linkml_import.cljs`).

   Callable from the JS console:
     frontend.handler.db_based.export.import_linkml_schema(yamlString)

   Returns a promise that resolves to {classCount, propertyCount}."
  [yaml-string]
  (p/let [parsed (js->clj (yaml/load yaml-string))
          result (state/<invoke-db-worker :thread-api/import-linkml
                                           (state/get-current-repo)
                                           parsed)]
    (notification/show!
     (str "Imported LinkML: " (:class-count result) " classes, "
          (:property-count result) " properties.")
     :success)
    (clj->js result)))

(defn ^:export export-linkml-schema
  "Walks every class that transitively extends :logseq.class/Schema and
   emits a LinkML YAML schema covering them. The YAML is copied to the
   clipboard and downloaded as a file so the user can pipe it to
   `gen-pydantic` for runtime Pydantic models.

   Surfaced via the export menu in the UI and callable from the JS
   console as `frontend.handler.db_based.export.export_linkml_schema()`."
  []
  (p/let [result (state/<invoke-db-worker :thread-api/export-edn
                                          (state/get-current-repo)
                                          {:export-type :linkml})
          yaml (:yaml result)]
    (when yaml
      (.writeText js/navigator.clipboard yaml)
      (println yaml)
      (let [repo (state/get-current-repo)
            data-str (str "data:text/yaml;charset=utf-8,"
                          (js/encodeURIComponent yaml))
            filename (file-name repo :yaml)]
        (when-let [anchor (gdom/getElement "download-as-db-edn")]
          (.setAttribute anchor "href" data-str)
          (.setAttribute anchor "download" filename)
          (.click anchor)))
      (notification/show! (t :export/linkml-schema-exported) :success))))
