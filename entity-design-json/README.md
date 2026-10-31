# Entity draft JSON

ET9 explicitly registers standard-catalog-v2@2; E1.A adds standard-document-v1@1
in the same schemaVersion=1 envelope.
E2 reference fields round-trip for both recipes in that same envelope; target facts
and canonical Java source names belong to the separate project context.
E3.A adds envelope v2 with a required nullable `owner`, using the separate packaged
`entity-draft-v2.schema.json`. Both current recipes are registered; document owner
input remains exportable but fails semantic validation. V1 rejects even owner=null;
encoding a v1 draft with non-null owner fails explicitly. No automatic version
upgrade or text normalization occurs, and old v1 fixture output is preserved.
The captured schema/resource and standard-catalog-v1@1 are preserved. Mixed/unknown
recipe references and HANDOFF_ONLY profiles are rejected; decoding never upgrades
an old draft or serializes its runtime context. See the [G1 handoff](../docs/architecture/entity-design-g1-handoff.md).

Neutral JSON v1 codec for `entity-design-core`. Java 21, no application parent,
Spring, JPA, Vaadin or platform starter. Runtime dependencies are the immutable
draft model and Jackson 2; the model itself keeps its dependency-free contract.

`DraftJsonCodec.decode(byte[] | String | InputStream)` validates the packaged
core envelope before binding to fixed DTO classes. No polymorphic typing,
class resolution from input, file paths or semantic correction. The stream API
reads at most 1 MiB + one byte; callers own/close their input stream.

The codec rejects duplicate keys/UUIDs, unknown structural properties,
unsupported schema/recipe versions, invalid JSON types, noncanonical UUIDs,
invalid UTF-8/Unicode and envelope limits (depth 32, text 4096 code points,
50 sections, 1000 fields total). Its private schema interpreter covers only the
keywords in the packaged v1 schema; it is not a general JSON Schema service.

`encode(EntityDraft)` produces deterministic UTF-8 bytes. Semantically invalid
names, unknown field type symbols, unavailable references and raw constraints
are retained. The application runs the single core validator against its current
project context after import. Project catalogs, computed suggestions, validation
results and UI state are never part of the document.

Build fresh core first, then run `mvn -f entity-design-json/pom.xml clean install`.
Tests consume the normative core fixtures and cover round-trips and rejection
boundaries. Application integration checks are in
`scripts/test-entity-draft-json.ps1`.
