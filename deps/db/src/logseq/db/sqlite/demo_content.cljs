(ns logseq.db.sqlite.demo-content
  "Demo seeding for fresh DB graphs: a Reading Queue domain that exercises
   every refinement field plus the #Schema meta-tag, with a walkthrough page
   that points users at what to look at and try.

   Loaded once at graph creation by `db_core`. Users can delete any of
   these nodes; nothing else in the system depends on them.

   What gets created:
   - 4 schema-graded classes: Item, Book, Article, InProgress
   - 8 user properties exercising every refinement field
   - 3 instances (2 books + 1 article)
   - A 'Demo: Schema Sketchpad' walkthrough page"
  (:require [datascript.core :as d]
            [logseq.db.sqlite.build :as sqlite-build]))

(def demo-edn
  "The sqlite-build EDN for the entire demo. Mirrors the shape used by
   `sqlite-build/create-blocks`."
  {:properties
   ;; --- Item slots (inherited by Book + Article) ----------------------
   {:user.property/title
    {:logseq.property/type :default
     :build/properties
     {:logseq.property.refinement/min-length 1
      :logseq.property.refinement/max-length 200
      :logseq.property.refinement/required? true}}

    :user.property/added-on
    {:logseq.property/type :date
     :build/properties {:logseq.property.refinement/required? true}}

    ;; --- Book-specific ----------------------------------------------
    :user.property/author
    {:logseq.property/type :default
     :db/cardinality :db.cardinality/many
     :build/properties {:logseq.property.refinement/min-length 1}}

    :user.property/page-count
    {:logseq.property/type :number
     :build/properties
     {:logseq.property.refinement/min-value 1
      :logseq.property.refinement/max-value 50000
      :logseq.property.refinement/numeric-kind "int"}}

    ;; --- Article-specific -------------------------------------------
    :user.property/url
    {:logseq.property/type :url
     :build/properties {:logseq.property.refinement/pattern "^https?://"}}

    ;; --- Shared discriminator. Closed values let each instance pick
    ;;     "book" or "article"; the LinkML export will emit it as an
    ;;     enum-typed slot. ---------------------------------------------
    :user.property/kind
    {:logseq.property/type :default
     :build/closed-values [{:value "book"} {:value "article"}]}

    ;; --- InProgress mixin slots ------------------------------------
    :user.property/started-on
    {:logseq.property/type :date}

    :user.property/fraction-done
    {:logseq.property/type :number
     :build/properties
     {:logseq.property.refinement/min-value 0
      :logseq.property.refinement/max-value 1
      :logseq.property.refinement/numeric-kind "float"}}}

   :classes
   {:Item
    {:block/title "Item"
     :build/class-extends [:logseq.class/Schema]
     :build/class-properties [:user.property/title :user.property/added-on]}

    :Book
    {:block/title "Book"
     :build/class-extends [:Item]
     :build/class-properties [:user.property/author
                              :user.property/page-count
                              :user.property/kind]}

    :Article
    {:block/title "Article"
     :build/class-extends [:Item]
     :build/class-properties [:user.property/url
                              :user.property/kind]}

    :InProgress
    {:block/title "InProgress"
     :build/class-extends [:logseq.class/Schema]
     :build/class-properties [:user.property/started-on
                              :user.property/fraction-done]}}

   :pages-and-blocks
   [;; -- Instances --
    {:page {:block/title "Gravity's Rainbow"
            :build/tags [:Book]
            :build/properties
            {:user.property/title "Gravity's Rainbow"
             :user.property/added-on [:build/page {:build/journal 20260214}]
             :user.property/author #{"Thomas Pynchon"}
             :user.property/page-count 760
             :user.property/kind "book"}}}

    {:page {:block/title "Infinite Jest"
            :build/tags [:Book :InProgress]
            :build/properties
            {:user.property/title "Infinite Jest"
             :user.property/added-on [:build/page {:build/journal 20260105}]
             :user.property/author #{"David Foster Wallace"}
             :user.property/page-count 1079
             :user.property/kind "book"
             :user.property/started-on [:build/page {:build/journal 20260420}]
             :user.property/fraction-done 0.18}}}

    {:page {:block/title "The Bitter Lesson"
            :build/tags [:Article]
            :build/properties
            {:user.property/title "The Bitter Lesson"
             :user.property/added-on [:build/page {:build/journal 20260301}]
             :user.property/url "http://incompleteideas.net/IncIdeas/BitterLesson.html"
             :user.property/kind "article"}}}

    ;; -- Walkthrough page --
    {:page {:block/title "Demo: Schema Sketchpad walkthrough"}
     :blocks
     [{:block/title "👋 This graph is preloaded with a worked example of the spec-sketchpad workflow. Open the **Item**, **Book**, **Article**, or **InProgress** tag pages and look at how they're built."}
      {:block/title "> The LinkML metamodel is itself written in LinkML — a mark of the language's expressive power and consistency."}
      {:block/title "## What's here"}
      {:block/title "**Schema-graded classes.** `Item`, `Book`, `Article`, `InProgress` all extend `schema` (directly or transitively). Only schema-graded classes appear in the LinkML export. Any extension chain of `#schema` is a valid schema root — `#schema/base` is itself schema-graded."}
      {:block/title "**Refinement properties.** Open any user property (Title, Author, Page count, URL, Fraction done) and click the gear icon — the dropdown's **Refinements** section shows the per-property pattern / min-max / numeric-kind / required toggles in action."}
      {:block/title "**Instances.** *Gravity's Rainbow*, *Infinite Jest*, *The Bitter Lesson*. Edit any property to see Malli enforcement live in the UI."}
      {:block/title "## Try"}
      {:block/title "1. Set a Title on an instance to the empty string — fails (`min-length 1`)."}
      {:block/title "2. Set `page-count` on a Book to `0`, `999999`, or a fractional value — fails (`min-value 1`, `max-value 50000`, `numeric-kind int`)."}
      {:block/title "3. Set `url` on the Article to something that doesn't start with `http://` or `https://` — fails (`pattern ^https?://`)."}
      {:block/title "4. Set `fraction-done` to `1.5` — fails (`max-value 1`)."}
      {:block/title "5. Open the JS console and run `frontend.handler.db_based.export$.export_linkml_schema()`. A YAML file downloads + the schema lands on your clipboard. Only the four Schema-graded classes appear in the output."}
      {:block/title "## Adding your own schema"}
      {:block/title "Make a new tag (`#YourClass`), open its page, set its **Extends** property to `schema`. Now properties you attach to it inherit the LinkML treatment — refinement controls appear automatically in the property config dropdown."}
      {:block/title "Source for this demo: `deps/db/src/logseq/db/sqlite/demo_content.cljs`. Delete this page if you want to start fresh — every demo node above can be deleted or edited freely."}]}]})

(defn seed!
  "Mutates `conn` with the demo graph content. Idempotent: if any of the
   demo classes already exist, returns nil without re-seeding. Catches
   and logs any creation error so a malformed demo doesn't brick the
   whole graph init."
  [conn]
  (let [db @conn
        already? (some? (some #(d/entity db [:db/ident %])
                              [:user.class/Item :user.class/Book :user.class/Article]))]
    (when-not already?
      (try
        (sqlite-build/create-blocks conn demo-edn)
        (catch :default e
          (js/console.error "demo-content seed failed:" e))))))
