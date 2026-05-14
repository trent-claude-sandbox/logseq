(ns frontend.components.class-schema-view
  "Nested-block style display + inline editing of a schema-graded class's
   slots + refinements. Mounted at the top of any tag page whose class
   extends :logseq.class/Schema. Replaces the per-property gear-icon
   dropdown as the primary editing surface; the dropdown is still
   reachable from the property's own page for less-frequent options.

   Editing model: click a refinement row to enter edit mode. Type a new
   value. Enter/blur commits via `db-property-handler/set-block-property!`
   on the property entity. Cancel via Escape; clear by saving an empty
   value (which un-sets the refinement)."
  (:require [datascript.impl.entity :as de]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [frontend.handler.db-based.property :as db-property-handler]
            [logseq.db :as ldb]
            [logseq.db.frontend.class :as db-class]
            [logseq.shui.ui :as shui]
            [rum.core :as rum]))

;; -- Per-slot refinement reader -----------------------------------------

(def ^:private refinement-row-spec
  "Each refinement field is one entry: [display-key getter-fn ident kind].
     - display-key — UI label (`:pattern`, `:min-value`, …)
     - getter-fn   — function from property entity → current value (or nil)
     - ident       — the datascript ident written via set-block-property!
                     (nil means the row is derived/read-only)
     - kind        — :text | :number | :readonly. Drives the inline editor."
  [[:type             #(some-> (:logseq.property/type %) name)                  nil :readonly]
   [:numeric-kind     :logseq.property.refinement/numeric-kind                  :logseq.property.refinement/numeric-kind :text]
   [:cardinality      (fn [p] (when (= :db.cardinality/many (:db/cardinality p)) "many")) nil :readonly]
   [:required         (fn [p] (when (:logseq.property.refinement/required? p) "true")) :logseq.property.refinement/required? :boolean]
   [:pattern          :logseq.property.refinement/pattern                       :logseq.property.refinement/pattern :text]
   [:min-value        :logseq.property.refinement/min-value                     :logseq.property.refinement/min-value :number]
   [:max-value        :logseq.property.refinement/max-value                     :logseq.property.refinement/max-value :number]
   [:min-length       :logseq.property.refinement/min-length                    :logseq.property.refinement/min-length :number]
   [:max-length       :logseq.property.refinement/max-length                    :logseq.property.refinement/max-length :number]
   [:literal          :logseq.property.refinement/literal                       :logseq.property.refinement/literal :text]
   [:description      (fn [p] (some-> (:logseq.property.refinement/description p))) :logseq.property.refinement/description :text]])

(defn- slot-rows
  "Return the populated kv-rows for a slot (a property entity).
   Each row is `[display-key value ident kind]` so the renderer can
   tell read-only fields from editable ones and pick the right editor."
  [property]
  (->> refinement-row-spec
       (keep (fn [[k getter ident kind]]
               (let [v (getter property)]
                 (when (and (some? v) (not= v ""))
                   [k v ident kind]))))))

(defn- editable-refinement-rows
  "Same as `slot-rows` but always emits every refinement key (including
   the unset ones) so the user can add a value to a not-yet-set field
   from the inline view. Returns `[display-key value ident kind]`."
  [property]
  (->> refinement-row-spec
       (filter (fn [[_ _ ident _]] ident)) ; drop read-only :type, :cardinality
       (map (fn [[k getter ident kind]]
              [k (getter property) ident kind]))))

(defn- closed-values
  "Return a list of human-readable closed-value strings for a property,
   or nil if it has none. Logseq stores closed values as referenced
   entities — each one's :block/title is the displayed value."
  [property]
  (->> (:property/closed-values property)
       (keep (fn [v]
               (or (:block/title v)
                   (some-> (:logseq.property/value v) str))))
       seq))

;; -- Rum components -----------------------------------------------------

(defn- coerce-input
  "Coerce a string from the inline editor into the right type for the
   property write. Strings/booleans/numbers are the three live kinds."
  [kind raw]
  (case kind
    :number  (let [n (js/parseFloat raw)] (if (js/isNaN n) nil n))
    :boolean (boolean (and raw (not= "" raw) (not= "false" raw)))
    raw))

(defn- commit-refinement!
  "Write the new value to the property entity via the existing
   set-block-property!. Empty string clears the refinement (so the row
   collapses to absent on next render)."
  [property ident kind raw]
  (let [coerced (when-not (= "" raw) (coerce-input kind raw))]
    (if (nil? coerced)
      (db-property-handler/remove-block-property! (:db/id property) ident)
      (db-property-handler/set-block-property! (:db/id property) ident coerced))))

(rum/defcs editable-kv-row < rum/reactive
  "A single kv-row. Read-only by default; clicking the value flips into
   an inline input. Enter / blur commits; Escape cancels; clearing the
   input + commit un-sets the refinement."
  (rum/local nil ::draft)
  [state property k v ident kind]
  (let [editing? (some? @(::draft state))
        readonly? (or (nil? ident) (= kind :readonly))
        commit! (fn []
                  (when-let [d @(::draft state)]
                    (commit-refinement! property ident kind d)
                    (reset! (::draft state) nil)))
        on-key (fn [^js e]
                 (case (.-key e)
                   "Enter" (commit!)
                   "Escape" (reset! (::draft state) nil)
                   nil))]
    [:div.ls-cs-kv.flex.items-baseline.gap-2.text-sm.opacity-75.py-0.5.pl-6
     [:span.ls-cs-key.font-mono.text-muted-foreground (str (name k) ":")]
     (cond
       readonly?
       [:span.ls-cs-val (str v)]
       editing?
       (shui/input
        {:size "sm"
         :auto-focus true
         :default-value (or @(::draft state) (str v))
         :type (case kind :number "number" "text")
         :class "ls-cs-edit h-6 w-40 text-xs"
         :on-change (fn [^js e] (reset! (::draft state) (.. e -target -value)))
         :on-key-down on-key
         :on-blur commit!})
       :else
       [:span.ls-cs-val.cursor-text
        {:on-click (fn [] (reset! (::draft state) (str (or v ""))))}
        (if (and (some? v) (not= v ""))
          (str v)
          [:span.opacity-50.italic "—"])])]))

(rum/defc kv-row
  "Backwards-compatible read-only wrapper (kept so callers that don't
   need editing can use the lightweight version)."
  [k v]
  [:div.ls-cs-kv.flex.items-baseline.gap-2.text-sm.opacity-75.py-0.5.pl-6
   [:span.ls-cs-key.font-mono.text-muted-foreground (str (name k) ":")]
   [:span.ls-cs-val (str v)]])

(rum/defc closed-values-block
  "Render a slot's closed values as an indented list of `- value` blocks
   under the slot. Mirrors LinkML's enum permissible-values shape."
  [values]
  [:div.ls-cs-values.border-l.border-muted.ml-2.pl-2.text-sm
   [:div.ls-cs-key.font-mono.text-muted-foreground.opacity-75 "values:"]
   (for [v values]
     [:div.ls-cs-value.pl-4.opacity-90 {:key (str v)}
      (str "• " v)])])

(rum/defc slot-block
  "One slot as a nested-block-styled row: the property name as a header
   line, an indented set of editable kv-rows for the refinements, then
   any closed-value enum.

   `inherited-from` (when set) tags the slot as inherited and renders
   the rows read-only — the user has to navigate to the parent class
   page to edit those (matches Logseq's tag-inheritance UX)."
  [property & {:keys [inherited-from]}]
  (let [pname (or (:block/title property)
                  (some-> property :db/ident name))
        rows (if inherited-from (slot-rows property) (editable-refinement-rows property))
        vals (closed-values property)]
    [:div.ls-cs-slot.py-1
     [:div.flex.items-baseline.gap-2
      [:span.ls-cs-bullet.opacity-50 "•"]
      [:span.ls-cs-slot-name.font-medium pname]
      (when inherited-from
        (shui/badge
         {:variant :outline :class "text-xs ml-1"}
         (str "from " (or (:block/title inherited-from)
                          (some-> inherited-from :db/ident name)))))]
     (when (seq rows)
       [:div.ls-cs-slot-rows.border-l.border-muted.ml-2.pl-2
        (if inherited-from
          (for [[k v] rows]
            (rum/with-key (kv-row k v) (str pname "/" (name k))))
          (for [[k v ident kind] rows
                ;; In editable mode we still hide empty read-only rows
                ;; (type / cardinality) when they'd be uninformative.
                :when (or ident (some? v))]
            (rum/with-key (editable-kv-row property k v ident kind)
              (str pname "/" (name k)))))])
     (when vals (closed-values-block vals))]))

(rum/defc parent-chain
  "Render the extends chain at the top of the schema view: arrows from
   this class up to :logseq.class/Schema, with each parent linked."
  [class]
  (let [ancestors (db-class/get-class-extends class)
        non-schema (remove #(= :logseq.class/Schema (:db/ident %)) ancestors)]
    (when (seq non-schema)
      [:div.ls-cs-extends.flex.items-baseline.gap-2.text-sm.opacity-75.py-0.5
       [:span.font-mono.text-muted-foreground "extends:"]
       (for [a non-schema]
         [:span.ls-cs-parent {:key (str (:db/ident a))}
          [:span.mr-1 (or (:block/title a) (some-> a :db/ident name))]])])))

(defn- inherited-slot-groups
  "Walk the class's extends chain and return a vector of
   `[ancestor [...slot-property-entities]]` tuples for slots defined on
   each ancestor (and not redeclared locally). The order matches the
   `is_a` chain LinkML emits, with mixins coming last."
  [class]
  (let [ancestors (->> (db-class/get-class-extends class)
                       (remove #(= :logseq.class/Schema (:db/ident %))))
        local-prop-idents (set (map :db/ident
                                    (filter de/entity?
                                            (:logseq.property.class/properties class))))]
    (vec
     (for [a ancestors
           :let [a-props (->> (:logseq.property.class/properties a)
                              (filter de/entity?)
                              ;; Don't re-show slots the child redeclares.
                              (remove #(contains? local-prop-idents (:db/ident %))))]
           :when (seq a-props)]
       [a a-props]))))

(rum/defcs class-schema-view < rum/reactive
  "Top-of-page schema summary. Only renders when the class transitively
   extends :logseq.class/Schema."
  [_state class]
  (when (and class (de/entity? class) (db-class/schema-graded? class))
    (let [class-live (db/sub-block (:db/id class))
          props (->> (:logseq.property.class/properties class-live)
                     (filter de/entity?))
          desc-val (or (some-> class-live :logseq.property/description :block/title)
                       (:logseq.property/description class-live))
          inherited (inherited-slot-groups class-live)]
      [:div.ls-class-schema-view.border.rounded-md.p-3.mb-3.bg-secondary/30
       [:div.flex.items-baseline.justify-between.mb-2
        [:div.flex.items-baseline.gap-2
         [:span.font-mono.text-muted-foreground.text-xs "schema"]
         [:span.font-medium (:block/title class-live)]]
        (shui/badge
         {:variant :secondary :class "text-xs"}
         (str (count props) " slot" (when (not= 1 (count props)) "s")))]
       (parent-chain class-live)
       (when desc-val
         [:div.ls-cs-description.text-sm.italic.opacity-75.mb-2.pl-2 desc-val])
       [:div.ls-cs-slots
        (for [p props]
          (rum/with-key (slot-block p) (str (:db/ident p))))]
       (when (seq inherited)
         [:div.ls-cs-inherited.mt-3.border-t.pt-2
          [:div.text-xs.text-muted-foreground.font-mono.mb-1 "inherited:"]
          (for [[ancestor a-props] inherited]
            [:div.ls-cs-inherited-group {:key (str (:db/ident ancestor))}
             (for [p a-props]
               (rum/with-key (slot-block p :inherited-from ancestor)
                 (str (:db/ident ancestor) "/" (:db/ident p))))])])
       [:div.ls-cs-hint.text-xs.text-muted-foreground.mt-2.pl-2
        "Read-only view of this tag's schema. To edit a slot, click into the property page or use the gear icon on its row in the table below."]])))
