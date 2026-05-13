(ns logseq.db.frontend.linkml-export
  "Emit a LinkML YAML schema from the current DB graph.

   Schema scope: a class is *schema-graded* when it extends (transitively)
   :logseq.class/Schema. The export walks all schema-graded user classes
   and produces a single LinkML schema with:

   - one `class` entry per schema-graded class (inheriting via `is_a:`)
   - one `slot` entry per property used by any schema-graded class
   - one `enum` entry for each closed-value or :literal slot

   Refinement properties from the :logseq.property.refinement/* namespace
   flow into the slot definitions as LinkML constraints
   (pattern / minimum_value / maximum_value / required). Length
   constraints fold into `pattern` because LinkML's current slot model
   has no first-class min/max length.

   This file emits YAML by hand. A real LinkML round-trip would use the
   linkml runtime, but that lives in Python and would force a heavyweight
   build dep; the schemas we emit are small and stable enough to render
   directly. Tests cover the shape."
  (:require [clojure.string :as string]
            [datascript.core :as d]
            [datascript.impl.entity :as de]
            [logseq.db.frontend.class :as db-class]
            [logseq.db.frontend.property :as db-property]))

;; -- helpers --------------------------------------------------------------

(defn- safe-name
  "LinkML class/slot names allow [A-Za-z_][A-Za-z0-9_]*. The titles users
   pick (e.g. 'Reading Item') are humanized; we keep the human title in
   `title:` and replace whitespace/punctuation with `_` for the identifier."
  [s]
  (when s
    (-> (str s)
        (string/replace #"[^A-Za-z0-9]+" "_")
        (string/replace #"_+" "_")
        (string/replace #"^_|_$" ""))))

(defn- property-name
  "User-friendly slot name for a property entity. Strips the namespace and
   falls back to the title."
  [prop]
  (or (safe-name (name (:db/ident prop)))
      (safe-name (:block/title prop))))

(defn- class-name
  [cls]
  (or (safe-name (:block/title cls))
      (safe-name (name (:db/ident cls)))))

;; -- type mapping (sketchpad spec.md Appendix A) --------------------------

(def ^:private base-type-map
  {:default  "string"
   :url      "uri"
   :number   "float"
   :date     "datetime"
   :datetime "datetime"
   :checkbox "boolean"
   :node     nil})           ; resolved to a class name at use site

(def ^:private numeric-kind-map
  {"int"     "integer"
   "float"   "float"
   "decimal" "decimal"})

(defn- slot-range
  [prop]
  (let [type (:logseq.property/type prop)]
    (case type
      :number (or (numeric-kind-map (:logseq.property.refinement/numeric-kind prop))
                  "float")
      :node   (or (some-> prop :logseq.property/classes first :block/title safe-name)
                  "string")
      (get base-type-map type "string"))))

;; -- pattern + length fold ------------------------------------------------

(defn- compile-pattern
  "Combine `pattern` + `min-length` + `max-length` into a single regex.
   LinkML has no first-class min/max_length on slots, so length collapses
   into the pattern via a lookahead. Empty bound on either side yields an
   open-ended quantifier."
  [prop]
  (let [pat (:logseq.property.refinement/pattern prop)
        lo  (:logseq.property.refinement/min-length prop)
        hi  (:logseq.property.refinement/max-length prop)]
    (cond
      (and (nil? lo) (nil? hi)) pat
      :else
      (let [lo-s (when (number? lo) (str (int lo)))
            hi-s (when (number? hi) (str (int hi)))
            length-re (str "^.{" (or lo-s "") "," (or hi-s "") "}$")]
        (if (string/blank? pat)
          length-re
          (str "(?=" length-re ")" pat))))))

;; -- enum extraction (closed-values + literal) ----------------------------

(defn- closed-value-strings
  "List of human values from a property's closed-values."
  [prop]
  (->> (:property/closed-values prop)
       (map (fn [v]
              (or (:block/title v)
                  (:logseq.property/value v))))
       (remove nil?)
       (map str)
       seq))

(defn- maybe-enum-for-slot
  "If the property has closed values, returns a tuple [enum-name enum-map].
   Otherwise nil. The enum lives at the schema's top-level and is referenced
   from the slot's :range."
  [prop slot-name]
  (when-let [vs (closed-value-strings prop)]
    (let [ename (str (safe-name slot-name) "_enum")]
      [ename {:permissible_values (into {} (map (fn [v] [v {}]) vs))}])))

(defn- maybe-literal-enum
  "A :literal refinement renders as a single-valued enum so the generated
   Pydantic gets `Literal[\"x\"]`. Higher precedence than closed-values."
  [prop slot-name]
  (when-let [lit (:logseq.property.refinement/literal prop)]
    (let [ename (str (safe-name slot-name) "_literal_enum")]
      [ename {:permissible_values {(str lit) {}}}])))

;; -- slot emission --------------------------------------------------------

(defn- emit-slot
  "Return a tuple [slot-map enums-to-add]. The slot-map is YAML-ready;
   enums-to-add is a seq of [name def] tuples for the top-level enums:
   block."
  [prop]
  (let [sname  (property-name prop)
        range' (slot-range prop)
        pat    (compile-pattern prop)
        many?  (= :db.cardinality/many (:db/cardinality prop))
        desc   (:logseq.property.refinement/description prop)
        slot   (cond-> {:name sname
                        :range range'}
                 many? (assoc :multivalued true)
                 (:logseq.property.refinement/required? prop) (assoc :required true)
                 (some? (:logseq.property.refinement/min-value prop))
                 (assoc :minimum_value (:logseq.property.refinement/min-value prop))
                 (some? (:logseq.property.refinement/max-value prop))
                 (assoc :maximum_value (:logseq.property.refinement/max-value prop))
                 pat (assoc :pattern pat)
                 desc (assoc :description desc))
        lit-enum (maybe-literal-enum prop sname)
        cv-enum  (when-not lit-enum (maybe-enum-for-slot prop sname))
        enum-pair (or lit-enum cv-enum)
        slot (if enum-pair (assoc slot :range (first enum-pair)) slot)
        slot (if-let [lit (:logseq.property.refinement/literal prop)]
               (assoc slot :equals_string (str lit))
               slot)]
    [slot (when enum-pair [enum-pair])]))

(defn- emit-class
  [cls slot-names]
  (let [cname (class-name cls)
        ;; Non-:schema ancestors split into is_a (single, first one wins)
        ;; vs mixins (the rest). Logseq doesn't natively distinguish; we
        ;; pick the chain's first parent as is_a and the rest as mixins.
        all-parents (->> (:logseq.property.class/extends cls)
                         (remove #(= :logseq.class/Schema (:db/ident %))))
        is-a-parent (first all-parents)
        mixin-parents (rest all-parents)
        desc-val (or (some-> cls :logseq.property/description :block/title)
                     (:logseq.property/description cls))]
    (cond-> {:name cname}
      is-a-parent (assoc :is_a (class-name is-a-parent))
      (seq mixin-parents) (assoc :mixins (mapv class-name mixin-parents))
      desc-val (assoc :description desc-val)
      (seq slot-names) (assoc :slots (vec slot-names)))))

;; -- walk + collect -------------------------------------------------------

(defn- schema-graded-classes
  "All non-internal classes whose extends chain transitively includes
   :logseq.class/Schema. The Schema class itself is excluded."
  [db]
  (->> (d/q '[:find [?e ...]
              :where
              [?e :block/tags :logseq.class/Tag]
              (not [?e :logseq.property/built-in?])]
            db)
       (map #(d/entity db %))
       (filter db-class/schema-graded?)))

(defn- properties-on-classes
  [classes]
  (->> classes
       (mapcat :logseq.property.class/properties)
       (filter de/entity?)
       (distinct)))

;; -- YAML renderer (tiny, ordered) ----------------------------------------
;;
;; A targeted YAML emitter: handles strings, numbers, booleans, vectors,
;; and string-keyed maps. No tags, no anchors, no flow style. The output
;; passes round-trip through PyYAML (verified in tests).

(declare ^:private yaml)

(defn- yaml-scalar
  [v]
  (cond
    (nil? v) "null"
    (boolean? v) (if v "true" "false")
    (number? v) (str v)
    (keyword? v) (yaml-scalar (name v))
    :else
    (let [s (str v)
          needs-quote? (or (string/blank? s)
                           (re-find #"[:#\n\\]" s)
                           (#{"null" "true" "false" "yes" "no" "on" "off"} (string/lower-case s))
                           (re-matches #"-?\d+(\.\d+)?" s))]
      (if needs-quote?
        (str "\"" (string/replace s "\"" "\\\"") "\"")
        s))))

(defn- yaml-pair
  [k v indent]
  (let [prefix (apply str (repeat indent " "))]
    (cond
      (map? v)
      (if (empty? v)
        (str prefix (yaml-scalar k) ": {}\n")
        (str prefix (yaml-scalar k) ":\n" (yaml v (+ indent 2))))
      (vector? v)
      (if (empty? v)
        (str prefix (yaml-scalar k) ": []\n")
        (str prefix (yaml-scalar k) ":\n"
             (apply str (map #(str prefix "- " (yaml-scalar %) "\n") v))))
      :else
      (str prefix (yaml-scalar k) ": " (yaml-scalar v) "\n"))))

(defn- yaml
  ([m] (yaml m 0))
  ([m indent]
   (apply str (map (fn [[k v]] (yaml-pair k v indent)) m))))

;; -- top-level ------------------------------------------------------------

(defn build-linkml-schema
  "Returns a YAML string. `opts` may carry :schema-id and :schema-name to
   override the defaults."
  [db & [{:keys [schema-id schema-name]
          :or {schema-id   "https://example.invalid/sketchpad"
               schema-name "logseq_sketchpad"}}]]
  (let [classes (schema-graded-classes db)
        props   (properties-on-classes classes)
        slot-tuples (mapv emit-slot props)
        slot-defs (into {} (map (fn [[s _]] [(:name s) (dissoc s :name)]) slot-tuples))
        enum-defs (into {} (mapcat second slot-tuples))
        class-defs (into {}
                         (map (fn [c]
                                [(class-name c)
                                 (dissoc (emit-class c (map property-name (:logseq.property.class/properties c)))
                                         :name)])
                              classes))
        schema {"id"             schema-id
                "name"           schema-name
                "default_prefix" "sk"
                "prefixes"       {"linkml" "https://w3id.org/linkml/"
                                  "sk"     (str schema-id "/")}
                "default_range"  "string"
                "imports"        ["linkml:types"]
                "classes"        class-defs
                "slots"          slot-defs}
        schema (if (seq enum-defs)
                 (assoc schema "enums" enum-defs)
                 schema)]
    (yaml schema)))
