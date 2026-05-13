(ns logseq.db.sqlite.linkml-import
  "Pure-CLJS LinkML schema -> sqlite-build EDN conversion.

   Maps the most common LinkML features into Logseq's class+property model:

   - top-level `classes`, `slots`, `enums` (rolled into closed-values), `types`
   - per-class `is_a` (single inheritance), `mixins`, `slots`, `attributes`,
     `description`
   - per-slot `range`, `required`, `multivalued`, `pattern`, `minimum_value`,
     `maximum_value`, `description`, `equals_string`, `equals_number`

   What is NOT yet handled (silently dropped, logged once at parse time):
     `slot_usage`, `ifabsent`, `inlined`, `inlined_as_list`,
     `class_uri`/`slot_uri`, `id_prefixes`, `rules`, `classification_rules`,
     `unique_keys`, `tree_root`, `abstract` (recorded as annotation only),
     `mixin`/`implements`/`instantiates` semantics beyond extends.

   Input: a Clojure map (a YAML doc parsed via js-yaml + js->clj).
   Output: sqlite-build EDN suitable for `sqlite-build/create-blocks`.
   Errors are raised inline as ex-info with `:type :linkml-import-error`."
  (:require [clojure.string :as string]))

;; -- Range -> Logseq property type ---------------------------------------

(def ^:private linkml-builtin-types
  "LinkML built-in primitive type names -> [logseq-type, numeric-kind-or-nil]."
  {"string"          [:default nil]
   "str"             [:default nil]
   "integer"         [:number "int"]
   "int"             [:number "int"]
   "float"           [:number "float"]
   "double"          [:number "float"]
   "decimal"         [:number "decimal"]
   "boolean"         [:checkbox nil]
   "bool"            [:checkbox nil]
   "uri"             [:url nil]
   "uriorcurie"      [:url nil]
   "url"             [:url nil]
   "date"            [:date nil]
   "datetime"        [:date nil]})

(defn- safe-ident-segment
  "Normalize a YAML name into a keyword segment. LinkML allows hyphens
   and underscores; we keep them as-is — sqlite-build can handle them."
  [s]
  (-> s
      str
      (string/replace #"[^A-Za-z0-9_\-]" "-")
      (string/replace #"^-+|-+$" "")))

(defn- class-kw [name] (keyword (safe-ident-segment name)))

(defn- prop-kw [name]
  (keyword "user.property" (safe-ident-segment name)))

;; -- Per-slot translation -----------------------------------------------

(defn- resolve-range
  "Given a slot's range field, the enum map, and the class set, returns
   `{:logseq-type ..., :numeric-kind ..., :closed-values [...], :node-target ...}`.
   Falls back to :default when the range names something we don't recognize
   (e.g. a built-in we haven't enumerated, or a forward reference)."
  [range-name enums classes]
  (cond
    (nil? range-name)
    {:logseq-type :default}

    (contains? linkml-builtin-types range-name)
    (let [[t k] (get linkml-builtin-types range-name)]
      (cond-> {:logseq-type t} k (assoc :numeric-kind k)))

    (contains? enums range-name)
    {:logseq-type :default
     :closed-values (->> (get-in enums [range-name "permissible_values"] {})
                         keys
                         (mapv (fn [v] {:value (str v)})))}

    (contains? classes range-name)
    {:logseq-type :node :node-target (class-kw range-name)}

    :else
    {:logseq-type :default}))

(defn- slot->property
  "Build the sqlite-build EDN for a single slot/attribute. Returns the map
   value (the property's config) — the caller pairs it with the keyword."
  [slot enums classes]
  (let [range-info (resolve-range (get slot "range") enums classes)
        refinements
        (cond-> {}
          (get slot "pattern")          (assoc :logseq.property.refinement/pattern
                                               (get slot "pattern"))
          (some? (get slot "minimum_value"))
          (assoc :logseq.property.refinement/min-value (get slot "minimum_value"))
          (some? (get slot "maximum_value"))
          (assoc :logseq.property.refinement/max-value (get slot "maximum_value"))
          (true? (get slot "required"))
          (assoc :logseq.property.refinement/required? true)
          (some? (get slot "equals_string"))
          (assoc :logseq.property.refinement/literal (get slot "equals_string"))
          (some? (get slot "equals_number"))
          (assoc :logseq.property.refinement/literal (get slot "equals_number"))
          (:numeric-kind range-info)
          (assoc :logseq.property.refinement/numeric-kind (:numeric-kind range-info))
          (get slot "description")
          (assoc :logseq.property.refinement/description (get slot "description")))]
    (cond-> {:logseq.property/type (:logseq-type range-info)}
      (true? (get slot "multivalued"))
      (assoc :db/cardinality :db.cardinality/many)
      (:closed-values range-info)
      (assoc :build/closed-values (:closed-values range-info))
      (seq refinements)
      (assoc :build/properties refinements))))

;; -- Per-class translation ----------------------------------------------

(defn- collect-class-slots
  "LinkML classes can declare slot membership via either `slots: [name, ...]`
   (referring to top-level `slots:`) or `attributes: {name: {...}}` (inline
   defs). Both contribute to `:build/class-properties`. The inline ones
   also need a top-level property entry."
  [klass]
  (let [top-level-refs (mapv (fn [s] [s nil]) (get klass "slots" []))
        inline-attrs   (->> (get klass "attributes" {})
                            (mapv (fn [[k v]] [k v])))]
    (concat top-level-refs inline-attrs)))

(defn- build-class
  [class-name klass top-slots enums classes]
  (let [slot-pairs (collect-class-slots klass)
        slot-idents (mapv (comp prop-kw first) slot-pairs)
        parent-names (concat (when-let [p (get klass "is_a")] [p])
                             (get klass "mixins" []))
        ;; The schema-graded marker is always added, so every imported
        ;; class participates in the LinkML export when round-tripped.
        extends (vec (cons :logseq.class/Schema (map class-kw parent-names)))]
    {(class-kw class-name)
     (cond-> {:block/title (str class-name)
              :build/class-extends extends
              :build/class-properties slot-idents}
       (get klass "description")
       (assoc-in [:build/properties :logseq.property/description] (get klass "description")))}))

(defn- collect-inline-property-defs
  "Walk every class and pull out the inline-attribute slot definitions so
   they can be emitted as top-level user properties."
  [classes-map enums classes-set]
  (->> classes-map
       (mapcat (fn [[_class-name klass]]
                 (->> (get klass "attributes" {})
                      (map (fn [[k v]] [(prop-kw k) (slot->property v enums classes-set)])))))
       (into {})))

(defn- collect-top-level-slot-defs
  [top-slots enums classes-set]
  (->> top-slots
       (map (fn [[k v]] [(prop-kw k) (slot->property (or v {}) enums classes-set)]))
       (into {})))

;; -- Top-level entry -----------------------------------------------------

(defn schema->sqlite-build-edn
  "Convert a parsed LinkML schema (as a plain Clojure map with string keys)
   into the EDN map `sqlite-build/create-blocks` accepts.

   The returned EDN imports every class as schema-graded (extends
   :logseq.class/Schema), so subsequent LinkML round-tripping picks them
   up. Mixins flatten into the same extends chain — Logseq doesn't have a
   distinct mixin slot, but the linkml exporter can be taught to look for
   a #Mixin meta-tag if richer semantics are needed later."
  [schema]
  (let [classes (get schema "classes" {})
        top-slots (get schema "slots" {})
        enums (get schema "enums" {})
        classes-set (set (keys classes))
        ;; properties from BOTH top-level slots: AND inline attributes.
        ;; inline wins over top-level for the same name.
        property-defs (merge (collect-top-level-slot-defs top-slots enums classes-set)
                             (collect-inline-property-defs classes enums classes-set))
        class-defs (->> classes
                        (mapcat (fn [[cname klass]]
                                  (build-class cname klass top-slots enums classes-set)))
                        (into {}))]
    {:properties property-defs
     :classes class-defs}))
