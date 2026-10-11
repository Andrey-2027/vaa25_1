# platform-persistence

Shared persistence foundation for the platform. This artifact currently owns only
`org.ipro.crud.BaseEntity`, the `@MappedSuperclass` used by application and platform entities
for identifiers, auditing fields, and optimistic versioning. Feature-specific entities,
repositories, and persistence scans belong to their owning application or platform module.

`BaseEntity` implements `IdentifiableEntity<Long>` from the Java-only
`org.ipro:platform-identity-api` artifact. Its compile dependencies are limited to that neutral
contract, Jakarta Persistence/Validation, Spring Data JPA (auditing annotations/listener), and
Hibernate Core (proxy-safe equality behavior). This module deliberately has no Boot auto-
configuration and must not absorb report-owned persistence types.

## Ownership checks

`PersistenceModuleCompositionTest` fixes the module's reviewed production type and compile
dependency sets and asserts that it has no feature-specific persistence registration. The
application-level `PlatformPersistenceModuleTest`, `PersistenceTypeRegistrationTest`, and
`PersistenceRegistrationIT` check that JR entity/repository/scan remain application-owned and
that runtime registrations still contribute the expected types.

## Build

The module is an independent Maven project and depends on the sibling `crudui` reactor only for
`platform-identity-api`. Its local dependency order remains recorded in
`scripts/local-dependencies.json`.

The identity interface is generic; BaseEntity remains a non-generic class with concrete
Long getters/setters, IDENTITY generation, Long optimistic version and unchanged audit
mapping. Subclasses inherit the Long identity contract without repeating implements.

Generic identity does not make BaseEntity generic or enable String/UUID primary keys in
the current persistence/CRUD pipeline. Its concrete Long methods have compiler-generated
Object bridges for the new interface; this does not remove the need to rebuild consumers
of the old interface descriptors. No PK/FK, generator or data migration is introduced.
See the [consumer migration guide](../docs/architecture/identity-id-consumer-guide.md)
for the coordinated binary update, supported boundaries and remaining runtime bridges.

`BaseEntityPersistenceTest` verifies the JPA metamodel, insert/reload/update, real Hibernate
proxy/detached equality, and the concrete methods/compiler bridges on H2. H2 is test-only.
