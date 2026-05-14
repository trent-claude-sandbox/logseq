(ns frontend.components.class-schema-view
  "Read-only nested-block style display of a schema-graded class's slots
   + refinements. Mounted at the top of any tag page whose class extends
   :logseq.class/Schema. Replaces (eventually) the per-property gear-icon
   dropdown as the primary editing surface for slots; for now it's
   display-only, with edits still flowing through the existing dropdown
   on each property's own page.

   Why a separate component rather than reusing property-config:
   property-config is a *per-property* control surface. A class page wants
   to see *all* of its slots at once, mirroring how a LinkML schema
   YAML reads top-to-bottom. The nested-block shape (slot name → indented
   refinement kv-rows) is closer to that mental model and to the on-page
   outliner experience Logseq users expect."
  (:require [datascript.impl.entity :as de]
            [frontend.context.i18n :refer [t]]
            [frontend.db :as db]
            [logseq.db :as ldb]
            [logseq.db.frontend.class :as db-class]
            [logseq.shui.ui :as shui]
            [rum.core :as rum]))

;; -- Per-slot refinement reader -----------------------------------------

(def ^:private refinement-row-spec
  "Each refinement field becomes a `key: value` row when set on a slot.
   The order here is the display order on the slot block."
  [[:type             #(some-> (:logseq.property/type %) name)]
   [:numeric-kind     :logseq.property.refinement/numeric-kind]
   [:cardinality      (fn [p] (when (= :db.cardinality/many (:db/cardinality p)) "many"))]
   [:required         (fn [p] (when (:logseq.property.refinement/required? p) "true"))]
   [:pattern          :logseq.property.refinement/pattern]
   [:min-value        :logseq.property.refinement/min-value]
   [:max-value        :logseq.property.refinement/max-value]
   [:min-length       :logseq.property.refinement/min-length]
   [:max-length       :logseq.property.refinement/max-length]
   [:literal          :logseq.property.refinement/literal]
   [:description      (fn [p] (some-> (:logseq.property.refinement/description p)))]])

(defn- slot-rows
  "Return the populated kv-rows for a slot (a property entity)."
  [property]
  (->> refinement-row-spec
       (keep (fn [[k getter]]
               (let [v (getter property)]
                 (when (and (some? v) (not= v ""))
                   [k v]))))))

;; -- Rum components -----------------------------------------------------

(rum/defc kv-row
  [k v]
  [:div.ls-cs-kv.flex.items-baseline.gap-2.text-sm.opacity-75.py-0.5.pl-6
   [:span.ls-cs-key.font-mono.text-muted-foreground (str (name k) ":")]
   [:span.ls-cs-val (str v)]])

(rum/defc slot-block
  "One slot as a nested-block-styled row: the property name as a header
   line, then an indented set of kv-rows for the refinements that are set."
  [property]
  (let [pname (or (:block/title property)
                  (some-> property :db/ident name))
        rows (slot-rows property)]
    [:div.ls-cs-slot.py-1
     [:div.flex.items-baseline.gap-2
      [:span.ls-cs-bullet.opacity-50 "•"]
      [:span.ls-cs-slot-name.font-medium pname]]
     (when (seq rows)
       [:div.ls-cs-slot-rows.border-l.border-muted.ml-2.pl-2
        (for [[k v] rows]
          (rum/with-key (kv-row k v) (str pname "/" (name k))))])]))

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

(rum/defcs class-schema-view < rum/reactive
  "Top-of-page schema summary. Only renders when the class transitively
   extends :logseq.class/Schema."
  [_state class]
  (when (and class (de/entity? class) (db-class/schema-graded? class))
    (let [class-live (db/sub-block (:db/id class))
          props (->> (:logseq.property.class/properties class-live)
                     (filter de/entity?))
          desc-val (or (some-> class-live :logseq.property/description :block/title)
                       (:logseq.property/description class-live))]
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
       [:div.ls-cs-hint.text-xs.text-muted-foreground.mt-2.pl-2
        "Read-only view of this tag's schema. To edit a slot, click into the property page or use the gear icon on its row in the table below."]])))
