# Entity design core

P8.1 supplies immutable Java 21 drafts, stable structural UUIDs, pure edit
operations, shared suggestions and a contextual semantic validator. The versioned
JSON envelope and first recipe are included as resources. There are no runtime
dependencies; JUnit/Jackson are test-only. The editor and production JSON codec
follow in P8.2/P8.4. `org.ipro.design.generation` supplies the preview-only
`standard-catalog-v2@2` and `standard-document-v1@1` root plans through
`EntityJavaGenerator`; it has no IO and no compile
or file-apply gate. Those remain G1.2/G1.3.

E1.A adds recipe-specific inherited/reserved fields and publications: catalog
code/name, document number/date. The document uses `StandardDocumentEntity` and
inherited DOCUMENT_NUMBER/date numbering, generator `document-java-v1` and
`plan-fingerprint-v2`. Catalog bytes/fingerprint v1 and the legacy `create()` remain
compatible. E2 adds optional/required references to existing managed Long-ID roots:
the E2 dispatcher uses `catalog-java-v2` / `document-java-v2`, both fingerprint
v2. Reference types use confirmed canonical source names, qualified to avoid import
collisions. The fixed `CatalogJavaGenerator` retains scalar-only v1 output,
fingerprint and diagnostics. Sections and subsystems still produce addressable
generator refusals. See [extension contracts](../docs/architecture/entity-generation-g1-extension-contracts.md).

E3.A adds an independent nullable `OwnerDraft`, resolved `CheckedStructure.Owner`
and `Projection.owner` binding. Catalog owners require a known existing CATALOG
Long-ID root; documents reject a non-null owner. The current dispatcher uses
`catalog-java-v3` / `document-java-v3` and F2, with full owner input, checked binding
and portable owner facts. Owners publish at order 30 and shift subject fields to
40+. Envelope v2 has a required nullable owner, while v1 stays unchanged in the
JSON codec. Legacy constructors and F1 remain supported. Runtime acceptance and
owner UI are separate E3.C/E3.B work.

- Coordinates: `org.ipro:entity-design-core:1.0-SNAPSHOT`.
- Resources: `org/ipro/design/schema/entity-draft-v1.schema.json` and
  `org/ipro/design/recipes/standard-catalog-v1.json`.
- Fixtures in `src/test/resources/org/ipro/design/fixtures` include incomplete and
  semantically invalid drafts that MUST remain exportable.
- `../scripts/test-entity-design-contract.ps1` checks envelope acceptance/rejection,
  source recipe facts, bootstrap fingerprint and independent packaging.
- `../scripts/test-entity-design-core.ps1` also requires non-skipped unit tests and
  checks the built JAR with `jdeps` (only `java.base` and `java.compiler`).
- The module is a standalone entry in the local dependency bootstrap. It is not
  part of a runtime starter or platform BOM. The application consumes the model
  directly in the P8.2 Workspace draft editor.

`DraftOperations` creates and edits immutable snapshots. Root fields use a null
section ID; section fields use their UUID. Reorder destinations are indices, but
edit/selection/diagnostic identities are UUIDs. Operations reject missing targets,
identity changes, duplicate allocated IDs and additions beyond recipe limits.
Semantic errors remain editable. Type changes retain previous constraints/references.

`DraftValidator.validate(draft, context)` returns addressable `DraftProblem`s and,
only without errors, a `CheckedStructure`. The result contains effective names,
typed constraints and reference type IDs. `appliesTo(draft, context)` compares the
entire immutable input and context, including version labels; callers must discard
stale results. UI revision/dirty state belongs to the future app-side session.

`ProjectDesignContext` supplies immutable facts. Incomplete name catalogs, unknown
reference capabilities and absent AUTO package defaults block structural approval.
No metadata scan, class loading, file access or business lookup happens in the core.
The v1 storage profile uses lowercase ASCII, at most 63 characters and a small
reserved-word guard; actual database/schema/file/compile checks belong to generation.

The validator catches duplicate UUIDs and aggregate field limits for in-memory
callers too. The P8.4 importer must additionally check JSON keys, envelope versions,
byte/depth limits and canonical IDs before replacing a current draft. Test fixture
hydration through Jackson is not a production import/export API.

The design recipe captured the standard base with role `INTERNAL`. Both standard
bases are now registered as `APP_API`, so applications can declare bare
`StandardCatalogEntity`/`StandardDocumentEntity` roots; unannotated `BaseEntity`
remains internal. Application API access is readiness for declared roots, not
permission to bypass the registry through reflection or copied base fields. The
recipe keeps `generationEnabled=false`; generation still requires its planned gate.

See [P8.0 contract](../docs/plans/entity-explorer-p8-contract.md) and
[P8 plan](../docs/plans/entity-explorer-p8-draft-plan.md),
[P8.1 evidence](../docs/plans/entity-explorer-p8-1-evidence.md).

ET9 adds RuntimeDesignSnapshot, versioned recipe readiness and
CheckedStructure.applicationProjection(). Capture v1 is unchanged; explicit
standard-catalog-v2@2 uses the same schemaVersion=1 envelope and requires current
runtime facts. The nine-argument ProjectDesignContext constructor remains available.
create() still creates capture v1; create(RecipeCatalog.STANDARD_CATALOG_V2) opts in.
Every recipe keeps generation disabled. Handoff-only profiles are rejected as drafts
until their own input/operation contract exists.

The optional entity-design-runtime artifact supplies checked backend facts. It
depends on this module, never the reverse. Snapshot data is not part of EntityDraft
JSON. See [G1 handoff](../docs/architecture/entity-design-g1-handoff.md).
