(ns logseq.db.sqlite.linkml-docs
  "Condensed LinkML documentation seeded as a small set of Logseq pages
   alongside the demo graph. Sources at https://linkml.io/linkml/.

   This is intentionally a digest — the original docs are long and live
   at the URLs cited per page. The goal is for users opening a fresh
   sketchpad to have the key concepts at their fingertips without
   leaving Logseq.

   Pages are tagged with a built-in `#linkml/doc` so they cluster in the
   sidebar. Delete any of them safely; nothing else depends on them."
  (:require [datascript.core :as d]
            [logseq.db.sqlite.build :as sqlite-build]))

(def docs-edn
  "sqlite-build EDN producing a `#linkml/doc` class + a set of doc pages
   tagged with it. Page bodies are short markdown-styled blocks."
  {:classes
   {:LinkmlDoc
    {:block/title "linkml/doc"
     :build/class-extends [:logseq.class/Schema]}}

   :pages-and-blocks
   [;; ----- 1. Overview ------------------------------------------------
    {:page {:block/title "LinkML: overview"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "**LinkML** is a *Linked Data Modeling Language* — a schema language for describing data structures with class+slot inheritance, primitive + enum types, and rich constraint expressivity."}
      {:block/title "> The LinkML metamodel is itself written in LinkML — a mark of the language's expressive power and consistency."}
      {:block/title "**Core data model.** A LinkML schema declares: `classes` (records / object shapes), `slots` (typed fields, reusable across classes), `enums` (closed-value sets), and `types` (refined primitives). All four are themselves modeled as LinkML classes inside the metamodel."}
      {:block/title "**What LinkML adds over JSON Schema.** Named slots reusable across classes, single + multiple inheritance, mixins, enum semantics with permissible-value-level metadata, designators for discriminated unions, rules / classification rules, units, identifiers, and a code-generator ecosystem (Pydantic, JSON Schema, OWL, GraphQL, SQL DDL, Rust, …)."}
      {:block/title "**What this sketchpad supports.** Each LinkML schema maps to a #schema-extending Logseq tag; each slot maps to a Logseq property; the eight :logseq.property.refinement/* fields cover pattern, min/max, length, numeric-kind, literal, required, description."}
      {:block/title "Source: https://linkml.io/linkml/intro/overview.html"}]}

    ;; ----- 2. Schemas: Classes ----------------------------------------
    {:page {:block/title "LinkML: classes"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "A LinkML **class** is a named record — a set of slots that an instance must (or may) populate. Classes form a hierarchy via `is_a` (single inheritance) and `mixins` (multiple-inheritance for slot composition)."}
      {:block/title "Key fields on a class: `name`, `is_a`, `mixins`, `abstract`, `mixin` (the class is itself usable only as a mixin), `slots` (top-level slot names this class uses), `attributes` (inline slot definitions), `slot_usage` (per-class overrides on inherited slots), `description`, `class_uri`, `tree_root` (designates the top of a containment hierarchy)."}
      {:block/title "**Logseq mapping.** A user tag `#X` corresponds to a LinkML class `X`. Its `is_a` is the tag's *Extends* relation. Inline `attributes` ≈ tag-bound properties; reusable `slots` ≈ properties shared across multiple tags."}
      {:block/title "Source: https://linkml.io/linkml/schemas/models.html#classes"}]}

    ;; ----- 3. Schemas: Slots ------------------------------------------
    {:page {:block/title "LinkML: slots"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "A **slot** is a typed field. It's the LinkML equivalent of a Logseq property — and like Logseq properties, the same slot can be reused across many classes."}
      {:block/title "Common slot fields: `range` (the slot's type), `required`, `multivalued`, `identifier`, `pattern` (regex), `minimum_value`/`maximum_value`, `equals_string`/`equals_number` (literal constraint, also used as discriminator), `ifabsent` (default), `inlined`/`inlined_as_list` (inline rather than reference), `designates_type` (this slot identifies which subtype this instance is), `description`."}
      {:block/title "**Refinement mapping.** Slot constraints map directly to the `:logseq.property.refinement/*` keys:\n* `range` → property type + numeric-kind\n* `pattern` → refinement/pattern\n* `minimum_value` / `maximum_value` → refinement/min-value / max-value\n* `required` → refinement/required?\n* `equals_string` / `equals_number` → refinement/literal\n* `description` → refinement/description"}
      {:block/title "Source: https://linkml.io/linkml/schemas/slots.html"}]}

    ;; ----- 4. Schemas: Types ------------------------------------------
    {:page {:block/title "LinkML: types"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "**Types** are refined primitives. The built-in set: `string`, `integer`, `float`, `double`, `decimal`, `boolean`, `date`, `datetime`, `time`, `uri`, `uriorcurie`, `ncname`, `objectidentifier`. Schemas can define their own types via `typeof` (subtype of an existing type) and optional `pattern`, `minimum_value` etc."}
      {:block/title "**Logseq mapping.** The importer maps `string` → :default, `integer` → :number + numeric-kind int, `float` / `double` → :number + numeric-kind float, `decimal` → :number + numeric-kind decimal, `boolean` → :checkbox, `uri` / `uriorcurie` → :url, `date` / `datetime` → :date. Custom types defined via `typeof` are not yet remapped — they collapse to their base type and the refinement constraints are lifted onto each slot that uses them."}
      {:block/title "Source: https://linkml.io/linkml/schemas/types.html"}]}

    ;; ----- 5. Schemas: Enums ------------------------------------------
    {:page {:block/title "LinkML: enums"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "**Enums** are closed-value sets. Each `permissible_value` can carry its own description, meaning (URI), or annotations."}
      {:block/title "Dynamic enums populate themselves from an external source (e.g. an ontology subset reachable via `reachable_from`). The importer here handles only static enums; dynamic ones import as empty closed-value lists."}
      {:block/title "**Logseq mapping.** A slot whose `range` is an enum name maps to a Logseq property with `:build/closed-values` listing each permissible value. The export direction inverts this: any closed-value-bearing property becomes a top-level enum referenced by the slot's range."}
      {:block/title "Source: https://linkml.io/linkml/schemas/enums.html"}]}

    ;; ----- 6. Tutorial 01: Hello LinkML -------------------------------
    {:page {:block/title "LinkML tutorial 01 — Hello LinkML"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "The smallest possible schema: one class with inline attributes."}
      {:block/title "```yaml\nid: https://w3id.org/linkml/examples/personinfo\nname: personinfo\nclasses:\n  Person:\n    attributes:\n      id:\n      full_name:\n      aliases:\n      phone:\n      age:\n```"}
      {:block/title "In Logseq: a single tag `#Person` extending `#schema`, with five untyped text properties. Open this graph's `#Person` page (it's already here from the demo) for the lived-in version."}
      {:block/title "Source: https://linkml.io/linkml/intro/tutorial01.html"}]}

    ;; ----- 7. Tutorial 02: Containers ----------------------------------
    {:page {:block/title "LinkML tutorial 02 — Containers"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "Tutorial 02 adds a `Container` class with `tree_root: true` — the canonical wrapper class that lists every instance in a serialized data file. `inlined_as_list` makes the persons embed by value rather than by reference."}
      {:block/title "```yaml\nclasses:\n  Container:\n    tree_root: true\n    attributes:\n      persons:\n        multivalued: true\n        inlined_as_list: true\n        range: Person\n```"}
      {:block/title "**Logseq mapping.** Logseq's data model is graph-shaped, not tree-shaped. `tree_root` and `inlined_as_list` don't map to anything operational on the Logseq side — they're hints to the exporter for the final serialized data format. The importer records them as annotations on the class."}
      {:block/title "Source: https://linkml.io/linkml/intro/tutorial02.html"}]}

    ;; ----- 8. Tutorial 03: Constraints --------------------------------
    {:page {:block/title "LinkML tutorial 03 — Constraints"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "Tutorial 03 introduces the constraints already covered by this sketchpad's refinements: `identifier`, `required`, `multivalued`, `pattern`, `minimum_value`, `maximum_value`, `range: integer`."}
      {:block/title "```yaml\nclasses:\n  Person:\n    attributes:\n      id:\n        identifier: true\n      full_name:\n        required: true\n      phone:\n        pattern: \"^[\\\\d\\\\(\\\\)\\\\-]+$\"\n      age:\n        range: integer\n        minimum_value: 0\n        maximum_value: 200\n```"}
      {:block/title "**Logseq mapping.** Each of these maps 1:1 to a `:logseq.property.refinement/*` field. Open the **Page count** or **URL** property in the demo to see the same controls in the property config dropdown."}
      {:block/title "Source: https://linkml.io/linkml/intro/tutorial03.html"}]}

    ;; ----- 9. Tutorial 07: Inheritance + mixins -----------------------
    {:page {:block/title "LinkML tutorial 07 — Inheritance and mixins"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "**`is_a`** is single inheritance: `Person is_a NamedThing` means every Person inherits NamedThing's slots."}
      {:block/title "**`mixins`** is multiple inheritance for slot composition. `Person mixins [HasAliases]` pulls in HasAliases's slots without making it a subtype."}
      {:block/title "**`abstract: true`** marks a class as not-instantiable on its own — only its subclasses are realized as data."}
      {:block/title "```yaml\nclasses:\n  NamedThing:\n    abstract: true\n    attributes:\n      id: {identifier: true}\n      full_name:\n  HasAliases:\n    mixin: true\n    attributes:\n      aliases: {multivalued: true}\n  Person:\n    is_a: NamedThing\n    mixins: [HasAliases]\n    attributes:\n      phone:\n      age: {range: integer}\n```"}
      {:block/title "**Logseq mapping.** is_a + mixins both flatten into Logseq's *Extends* multi-value relation. The LinkML exporter splits the chain back: the first non-`#schema` ancestor becomes `is_a`, the rest become `mixins`. `abstract` is currently dropped on import — TODO: a future `:logseq.property.refinement/abstract?` flag."}
      {:block/title "Source: https://linkml.io/linkml/intro/tutorial07.html"}]}

    ;; ----- 10. is_a vs mixins vs implements vs instantiates -----------
    {:page {:block/title "LinkML: implements vs instantiates vs is_a vs mixins"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "These four relations look similar but mean different things at different layers:"}
      {:block/title "**`is_a` — 'is a kind of'**. Class inheritance, like OOP single-parent inheritance. Forms a tree. Child gets all parent's slots. Data-level enforcement via slot propagation."}
      {:block/title "**`mixins` — 'also behaves like'**. Slot composition without the single-parent constraint. Forms a DAG. Like Rust traits / Java interfaces with default methods. Multiple mixins per class allowed."}
      {:block/title "**`implements` — 'conforms to the structure of'**. A *contract assertion*: this class promises to expose the slots declared on the target. Validator support is weak — the class must redeclare the slots; you don't get free inheritance."}
      {:block/title "**`instantiates` — 'is an instance of (at the metamodel level)'**. Governs which annotation tags are valid on a schema element. Not a data-level relation at all — it lives one level up in the LinkML metamodel."}
      {:block/title "The official howto specifically warns: don't use `is_a` for non-subtype relations (creates a fake hierarchy); don't expect `implements` to give you slots automatically (it doesn't); don't confuse `instantiates` (metadata-level) with `implements` (structural)."}
      {:block/title "**Logseq mapping today.** Only `is_a` + `mixins` are first-class. `implements` and `instantiates` flatten into extends (lossy but parseable) — a future iteration will give them their own marker tags."}
      {:block/title "Source: https://linkml.io/linkml/howtos/implements-instantiates-guide.html"}]}

    ;; ----- 11. Validation in Logseq -----------------------------------
    {:page {:block/title "LinkML: validation in this sketchpad"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "**Where validation happens.** Every property edit flows through Malli (`deps/db/.../malli_schema.cljs::validate-property-value`). The refinement constraints layer on top via `validate-refinements`. Failed edits are blocked at the outliner layer and surfaced as humanized errors (`outliner-property/validate-property-value`)."}
      {:block/title "**What users see on failure.** A warning-triangle icon next to the value, with a tooltip explaining *which* refinement failed (`must be ≤ 50000`, `must match pattern ^https?://`, etc.)."}
      {:block/title "**Why this is layered.** The Logseq core hands out a *boolean* answer for save-gate purposes. The humanized error is a *side channel* used by the UI. Both come from the same source-of-truth refinement validator, so the in-graph behavior and the LinkML-export-side validation report agree."}
      {:block/title "**Validating a whole subgraph.** From the JS console: `frontend.handler.db_based.export.export_linkml_schema()` produces YAML and `gen-pydantic schema.yaml > models.py` (run externally) gives you a Pydantic runtime gate. You can then `Model.model_validate(instance_dict)` over every exported instance for the cross-class invariants the Malli layer can't express."}
      {:block/title "**Validate-on-save deeper integration (open question).** The user-facing `Save invalid anyway` escape hatch is not yet wired. The TODO is to capture refusal events as `#linkml/validation-failure` nodes that link back to the offending property+value so the user can browse a queue of issues rather than fighting individual edits."}]}

    ;; ----- 12. Property-on-property (edge properties) ----------------
    {:page {:block/title "LinkML: property-on-property (edge/slot annotations)"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "LinkML calls them **slot annotations** (or, on a class-bound slot, **slot_usage**). The general idea: not just the *value* of a slot can carry metadata, but *the slot itself* can. Use cases: a regex constraint, a description, a unit hint, a UI label, a `relational_role` for SQL mapping."}
      {:block/title "**Logseq's native expression of this** is already property-on-property. Every entity in Logseq — including property entities — can carry properties. The `:logseq.property.refinement/*` keys are themselves property-on-property: `pattern`, `min-value`, `max-value`, `numeric-kind`, `literal`, `required?`, `description` are all properties **of** the property they refine."}
      {:block/title "**How to use it.** Open any user property page → click the gear icon → the `Refinements` group shows the property-on-property controls. Set values there; they live as datoms on the property entity and survive both EDN export and the LinkML emitter."}
      {:block/title "**LinkML modeling guide for the same shape:** https://linkml.io/linkml/howtos/model-property-graphs.html"}
      {:block/title "**Future work.** Edge properties between two *instances* (rather than slot annotations) require a different mechanism — a `LinkRecord` class or sqlite-build's `:build/edges`. Tracked separately."}]}

    ;; ----- 13. ER-diagram export ------------------------------------
    {:page {:block/title "LinkML: ER-diagram export (Mermaid)"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "**`gen-erdiagram`** is one of LinkML's standard generators; it produces a Mermaid `erDiagram` block from a schema. This fork has the equivalent in-app:"}
      {:block/title "```\nfrontend.handler.db_based.export.export_linkml_er_diagram()\n```"}
      {:block/title "Run that from the JS console. The Mermaid text is copied to the clipboard and downloaded as a `.mmd` file. Paste it into any Logseq block to render the diagram inline; commit it to a README to render on GitHub."}
      {:block/title "**Coverage.** One box per schema-graded class with its slot list (range + name + description); one `||--o{` edge per node-typed slot. Class hierarchy (`is_a`, `mixins`) is implicit in the slot inheritance and not drawn separately — for richer hierarchy diagrams, fall back to LinkML's own `gen-erdiagram` Python tool over the YAML."}
      {:block/title "**Logseq's #asset pattern.** The exported `.mmd` follows the same data-URL download convention as the LinkML YAML and graph-ontology EDN exports. A dedicated `#asset` storage attachment is a future improvement — for now the file is yours to commit wherever you want."}
      {:block/title "Source: https://linkml.io/linkml/generators/erdiagram.html"}]}

    ;; ----- 14. Nested-block schema authoring + templates --------------
    {:page {:block/title "Schema authoring: nested blocks + templates"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "**Two ways to author a schema in this sketchpad.** Both produce the same downstream data shape (#schema-graded classes + property-attached refinement constraints); they're alternative editing surfaces."}
      {:block/title "**A. Tag-page authoring (current default).** Make a tag (`#Book`), open its page, click the gear, dial in slots + refinements via the property-config dropdown. Good for incremental tweaks on an established schema."}
      {:block/title "**B. Nested-block schema-doc authoring.** Author the whole schema as a Logseq block tree in one page, then *materialize* it into real tags + properties. Good for drafting, for translating an existing LinkML YAML, or when block references (`((uuid))`) need to point between schema parts within the same doc."}
      {:block/title "**Block syntax for nested-block authoring** mirrors LinkML's YAML structure:"}
      {:block/title "```\n- Book #Class\n  - extends: Item\n  - description: a printed text artifact\n  - slots:\n    - title\n      - type: string\n      - required: true\n      - min-length: 1\n      - max-length: 200\n    - page-count\n      - type: number\n      - numeric-kind: int\n      - min-value: 1\n      - max-value: 50000\n    - tags\n      - type: string\n      - cardinality: many\n```"}
      {:block/title "**Recognized class metadata keys** (children of a `#Class` block): `extends` (single, or repeatable for mixins), `is_a` + `mixins` (LinkML aliases), `description`, `slots:` (children are slot defs)."}
      {:block/title "**Recognized slot keys** (children of a slot name block): `type` (string / number / integer / float / decimal / boolean / date / datetime / url / node), `required`, `multivalued` or `cardinality: many`, `pattern`, `min-value` / `max-value`, `min-length` / `max-length`, `numeric-kind` (int / float / decimal), `literal`, `description`."}
      {:block/title "**Materialize step.** Run `frontend.handler.db_based.export.materialize_schema_doc(blockUuid)` in the JS console (passing the schema-doc page's root block UUID). The slash command `/Materialize schema` is on the roadmap; for now use the JS path."}
      {:block/title "**Built-in templates.** Type `/template` and pick from: `schema-class` (drops a class stub), `schema-slot-text` / `schema-slot-number` / `schema-slot-date` / `schema-slot-url` / `schema-slot-node` (drop slot stubs with the right type pre-filled). Templates compose with the parser — the materialize step parses whatever block tree is there, including content inserted via templates."}
      {:block/title "**Block references inside a schema doc.** Inside one schema doc, `extends: ((block-uuid))` and `range: ((block-uuid))` are honored — Logseq's normal `((..))` reference syntax. The materializer dereferences each `(())` to the target block's class name. Across schema-docs, use `[[ClassName]]` (page-name reference) which compiles to a tag reference."}
      {:block/title "See: *Demo: nested-block schema authoring* (a sibling page that walks through the materialize flow end-to-end)."}]}

    ;; ----- 15. Auto-expanded schema definitions (design note) --------
    {:page {:block/title "Design note: auto-expanded schema definitions on tag pages"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "**Goal.** When you open a schema-graded class's tag page, the slot list + refinements + parent chain should be visible at a glance — no clicking around required."}
      {:block/title "**What exists today.** Logseq's tag pages show the class's slots in a properties panel + a Children list. Refinement details require clicking the gear icon per property."}
      {:block/title "**Proposed change.** On any tag whose extends chain transitively includes `#schema`, render a `Schema definition` block at the top of the page. The block shows: is_a chain, slot list with type + key refinements (required, pattern, min/max) inline, and a `Open as LinkML / Open as ER diagram` action row. Default-expanded; collapsible."}
      {:block/title "**Implementation sketch.** Add a Rum component `schema-summary-block` in `frontend.components.class`. Mount it at the top of the class-page renderer (`components/page.cljs`) when `db-class/schema-graded?` returns true. Source the data via the existing class entity + `:logseq.property.class/properties` traversal — no new datoms needed. Tested via the same Playwright e2e harness."}
      {:block/title "**Not yet implemented in this branch.** Documented here so the next iteration doesn't relitigate the question."}]}

    ;; ----- 15. Schema-driven data migration (research) ----------------
    {:page {:block/title "Open question: schema-driven data migration"
            :build/tags [:LinkmlDoc]}
     :blocks
     [{:block/title "**The setup.** A user sets `max-value: 50000` on `page-count`. The graph already has an instance with `page-count: 99999`. What now?"}
      {:block/title "**Three plausible behaviors:**\n1. **Reject the schema change.** Save fails until the user fixes the instance. Safest but loud — every schema tweak demands a data sweep first.\n2. **Accept silently.** The new constraint applies to future edits; existing data stays invalid and is surfaced via the warning-icon tooltip (current behavior).\n3. **Auto-migrate.** On a schema change, run a fix-up step that adjusts values to satisfy the new constraints (clip to range, drop fields, init to default). Risky but useful for tight iteration."}
      {:block/title "**Recommendation: option 2 by default + opt-in option 3.** Logseq's editing model is forgiving by nature; option 1 would be jarring. Option 3 is the right move for explicit `Reconcile this property` actions in the UI (one click to clip every out-of-range value to the new max). It must never run silently — surprise data modifications would erode trust."}
      {:block/title "**LinkML's stance.** LinkML treats schema migrations as out-of-band; it provides `linkml-convert --schema-old --schema-new` for batch transformations. We'd ship the equivalent via an additional in-app action driven off the property's refinement deltas. Not yet implemented."}
      {:block/title "**Special case: defaulting.** When a *new* required slot is added to a class that already has instances, those instances are now invalid. Option 3.5: silently insert each property's `ifabsent` value into existing instances. LinkML supports this natively; we'd thread it through `:logseq.property/default-value`."}]}]})

(defn seed!
  "Attach the LinkML doc pages to a fresh graph. Idempotent."
  [conn]
  (let [db @conn
        already? (some? (d/entity db :logseq.class/Schema))
        docs-already? (some? (d/entity db :user.class/LinkmlDoc))]
    ;; Only seed if the schema class is in place AND we haven't already
    ;; added the docs. This way the docs come along for the demo-content
    ;; seed but don't try to materialize on a graph that has no demo.
    (when (and already? (not docs-already?))
      (try
        (sqlite-build/create-blocks conn docs-edn)
        (catch :default e
          (js/console.error "linkml-docs seed failed:" e))))))
