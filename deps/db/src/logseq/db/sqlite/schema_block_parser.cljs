(ns logseq.db.sqlite.schema-block-parser
  "Parse a nested-block schema-doc (Logseq blocks authored in a YAML-mirror
   form) into sqlite-build EDN suitable for `sqlite-build/create-blocks`.

   The author writes the schema as a normal Logseq block tree:

       - Item #Class
         - extends: schema
         - description: a thing to read
         - slots:
           - title
             - type: string
             - required: true
             - min-length: 1
             - max-length: 200
           - page-count
             - type: number
             - numeric-kind: int
             - min-value: 1
             - max-value: 50000

   then runs the *Materialize schema* command. This namespace is the pure
   piece: input is a normalized tree (no datascript), output is the EDN
   the worker hands to sqlite-build. Tests cover every key + the
   reference-resolution rules; the materialize handler in
   `frontend.handler.db-based.schema-block` is the thin shell that pulls
   blocks from the DB, runs the parser, and transacts the result."
  (:require [clojure.string :as string]))

;; -- Line tokenization ---------------------------------------------------

(def ^:private kv-re
  "Recognize `key: value` block titles. Anything before the first `:` that
   looks like an identifier counts as a key — everything after as a value.
   Trailing `#Tag` annotations are stripped from the key side and kept as
   tags for the block-name side."
  #"^([A-Za-z][A-Za-z0-9_\-]*)\s*:\s*(.*)$")

(def ^:private tag-re #"#([A-Za-z][A-Za-z0-9_\-/]*)")

(defn- strip-tags
  "Return [text-without-#tags  list-of-tag-names]."
  [s]
  (let [tags (mapv second (re-seq tag-re s))
        clean (string/trim (string/replace s tag-re ""))]
    [clean tags]))

(defn- parse-title
  "Categorize a block title. Always returns a 4-tuple `[kind k v tags]`
   so destructuring on the 4th slot works uniformly:
     - `[:kv key value tags]` for `key: value`
     - `[:name name nil tags]` for a bare declaration (e.g. `Book #Class`)
   Tags are stripped from the leading text and returned without the `#`."
  [title]
  (let [[clean tags] (strip-tags (or title ""))]
    (if-let [[_ k v] (re-matches kv-re clean)]
      [:kv (keyword (string/lower-case k)) (string/trim v) tags]
      [:name clean nil tags])))

;; -- Value coercion ------------------------------------------------------

(defn- coerce-value
  "Cast a string value into the natural Clojure value. Numbers parse,
   booleans recognize true/false, everything else stays a string."
  [s]
  (cond
    (re-matches #"-?\d+" s)             (js/parseInt s 10)
    (re-matches #"-?\d+\.\d+" s)        (js/parseFloat s)
    (= "true" (string/lower-case s))    true
    (= "false" (string/lower-case s))   false
    :else s))

;; -- Slot translation ---------------------------------------------------

(def ^:private slot-builtin-types
  "Logseq property type name → [logseq-type, numeric-kind-or-nil]."
  {"string"   [:default nil]
   "str"      [:default nil]
   "text"     [:default nil]
   "number"   [:number nil]
   "integer"  [:number "int"]
   "int"      [:number "int"]
   "float"    [:number "float"]
   "double"   [:number "float"]
   "decimal"  [:number "decimal"]
   "boolean"  [:checkbox nil]
   "bool"     [:checkbox nil]
   "checkbox" [:checkbox nil]
   "date"     [:date nil]
   "datetime" [:date nil]
   "url"      [:url nil]
   "uri"      [:url nil]
   "node"     [:node nil]})

(defn- resolve-type
  [v]
  (or (slot-builtin-types (string/lower-case (or v "")))
      [:default nil]))

(defn- slot-children->config
  "Convert a sequence of child blocks (kv lines like `type: number`,
   `min-value: 1`) into a sqlite-build property-config map."
  [children]
  (let [pairs (->> children
                   (keep (fn [c]
                           (let [[kind k v _] (parse-title (:title c))]
                             (when (= kind :kv) [k (coerce-value v)])))))
        bag (into {} pairs)
        [base-type kind] (resolve-type (str (:type bag)))
        many? (or (= true (:multivalued bag))
                  (= "many" (some-> bag :cardinality string/lower-case)))
        refinements
        (cond-> {}
          (:pattern bag)     (assoc :logseq.property.refinement/pattern (str (:pattern bag)))
          (:min-value bag)   (assoc :logseq.property.refinement/min-value (:min-value bag))
          (:max-value bag)   (assoc :logseq.property.refinement/max-value (:max-value bag))
          (:min-length bag)  (assoc :logseq.property.refinement/min-length (:min-length bag))
          (:max-length bag)  (assoc :logseq.property.refinement/max-length (:max-length bag))
          (true? (:required bag))
          (assoc :logseq.property.refinement/required? true)
          (:literal bag)     (assoc :logseq.property.refinement/literal (str (:literal bag)))
          (:description bag) (assoc :logseq.property.refinement/description (str (:description bag)))
          kind               (assoc :logseq.property.refinement/numeric-kind kind)
          (:numeric-kind bag) (assoc :logseq.property.refinement/numeric-kind
                                     (string/lower-case (str (:numeric-kind bag)))))]
    (cond-> {:logseq.property/type base-type}
      many? (assoc :db/cardinality :db.cardinality/many)
      (seq refinements) (assoc :build/properties refinements))))

(defn- slot-name->prop-kw [n] (keyword "user.property" n))
(defn- class-name->kw [n] (keyword n))

(defn- parse-slot
  "A slot definition: name in the title (with optional #Tag annotations),
   constraint lines as :children."
  [block]
  (let [[kind nm _ _] (parse-title (:title block))
        slot-name (case kind
                    :kv  (name nm)
                    :name nm)
        cfg (slot-children->config (:children block))]
    {:name slot-name
     :ident (slot-name->prop-kw slot-name)
     :config cfg}))

;; -- Class translation --------------------------------------------------

(defn- class-meta-from-children
  "Pick out the metadata kv-lines (extends, description, etc.) from a
   class's children. Returns `{:extends [...] :description ... :mixins [...]}`."
  [children]
  (reduce
   (fn [acc child]
     (let [[kind k v _] (parse-title (:title child))]
       (case kind
         :kv (case k
               :extends     (update acc :extends (fnil conj []) (class-name->kw v))
               :is-a        (update acc :extends (fnil conj []) (class-name->kw v))
               :is_a        (update acc :extends (fnil conj []) (class-name->kw v))
               :mixins      (apply update acc :mixins (fnil conj [])
                                   (map class-name->kw (string/split v #",\s*")))
               :description (assoc acc :description (string/trim v))
               acc)
         acc)))
   {}
   children))

(defn- slots-block
  "Find the `slots:` child of a class (if any) and return its children
   (each of which is itself a slot block). Slots can also be declared
   inline as `slot: ...` lines, but the nested form is the recommended
   one."
  [class-children]
  (some (fn [c]
          (when (= :kv (first (parse-title (:title c))))
            (let [[_ k _ _] (parse-title (:title c))]
              (when (= k :slots) (:children c)))))
        class-children))

(defn- parse-class
  [block]
  (let [[_kind nm _ tags] (parse-title (:title block))
        children (:children block)
        meta (class-meta-from-children children)
        slot-blocks (or (slots-block children) [])
        slots (mapv parse-slot slot-blocks)
        ;; Every class extends :logseq.class/Schema so it participates
        ;; in the LinkML export. User-declared extends come first; if
        ;; they explicitly listed `schema` we drop the duplicate.
        extends (->> (concat (:extends meta) (:mixins meta))
                     (remove #(= :schema %))
                     (cons :logseq.class/Schema)
                     distinct vec)]
    {:name nm
     :ident (class-name->kw nm)
     :tags tags
     :class-map (cond-> {:block/title nm
                         :build/class-extends extends
                         :build/class-properties (mapv :ident slots)}
                  (:description meta)
                  (assoc-in [:build/properties :logseq.property/description]
                            (:description meta)))
     :slots slots}))

;; -- Top-level ---------------------------------------------------------

(defn parse-schema-doc
  "Parse a normalized block tree (`{:title ... :children [...]}`) representing
   a schema-doc page. Returns sqlite-build EDN ready for `create-blocks`.

   Only direct-child blocks with a `#Class` tag in their title are
   treated as class declarations — everything else (notes, free-form
   text) is left alone. Inside each class block, the `slots:` child is
   parsed for slot definitions and `key: value` siblings supply class
   metadata (`extends`, `description`, `mixins`)."
  [doc-tree]
  (let [class-blocks (->> (:children doc-tree)
                          (filter (fn [b]
                                    (let [[kind _ _ tags] (parse-title (:title b))]
                                      (and (= kind :name)
                                           (some #{"Class"} tags))))))
        parsed-classes (mapv parse-class class-blocks)
        property-defs (->> parsed-classes
                           (mapcat :slots)
                           (map (juxt :ident :config))
                           (into {}))
        class-defs (->> parsed-classes
                        (map (juxt :ident :class-map))
                        (into {}))]
    {:properties property-defs
     :classes class-defs}))
