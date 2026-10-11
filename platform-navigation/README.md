# Navigation backend — KI-1 / KI-2

Immutable projection of the existing initialized `SubsystemRegistry`. This module
does not scan application packages, edit subsystem membership, publish form routes
or grant access. It has no Vaadin or application dependency.

Applications implement `NavigationDefaultsContributor`. Each existing subsystem
and list item needs one explicit literal ID; visibility defaults to `true`:

```java
NavigationDefaultsContributor contributor = defaults -> defaults
    .subsystem(Directories.class, "directories")
    .list(Directories.class, UnitOfMeasurement.class, "units-list", false);
NavigationCatalog catalog = NavigationCatalog.build(registry, List.of(contributor));
NavigationTree tree = new NavigationResolver().resolve(catalog,
    Map.of("item:units-list", NavigationVisibility.SHOW), existingReadGate);
```

The registry must be initialized before `build`. Missing identities, duplicate
identities/targets, invalid IDs or declarations for nonexistent membership fail
the build. Contributor order cannot resolve ambiguity. IDs use ASCII
lower-kebab-case. Keep them when moving/renaming Java types or moving an item to
another subsystem; an intentional ID change requires migration of overrides.
The catalog does not keep the mutable source tree or contributor builder.

Profile keys are `subsystem:<id>` and `item:<id>`. `DEFAULT` inherits developer
visibility. `SHOW` still requires visible ancestors and the supplied read decision.
Unknown stored keys are reported by `staleKeys()` and otherwise ignored; the input
overrides are never mutated. The storage owner must preserve them until an explicit
reset and validate new overrides against `catalog.keys()`.

`NavigationTree` contains only visible items/nodes and removes empty subtrees.
`Node.groupsRecursive()` is the common source for a parent subsystem's tiles,
including visible descendants. Editor views use the complete `NavigationCatalog`.
There is no shared per-user state; read predicate failures propagate to the caller.
Opening forms and enforcing access continue to belong to existing services.

`META-INF/navigation-surface.txt` classifies the reviewed surface. Owner-side
tests cover metadata projection, visibility, stable IDs, storage and module composition.

## Optional profile storage (KI-2)

The module is inert unless `platform.navigation.enabled=true`. When enabled, its
auto-configuration registers the catalog, three internal JPA stores/repositories,
`NavigationAdministrationService`, `NavigationProfileReader` and the resolver.
It requires an initialized `SubsystemRegistry`, identity declarations, a transaction
manager and both application adapters. Missing adapters fail startup.

- `NavigationAdministrationAccess` decides whether the caller is an administrator.
  Every administrative operation checks it in its method body, before validation
  and database access; a direct call cannot bypass this check. There is no default
  permitting policy. The application implements the existing `ROLE_ADMIN` rule.
- `NavigationUserDirectory` supplies bounded canonical user lookup, existence
  validation and the current authenticated user's immutable ID. The module never
  imports the application's User entity or references its table. User keys are
  opaque strings of at most 190 characters. Navigation target keys have the same
  storage limit, checked at catalog startup.

The administration API creates/lists/reads/renames profiles, atomically replaces
known visibility overrides, resets overrides, assigns/reassigns/removes a user's
profile and reads assignments for the editor. Lookup limits are 1..50. Profile
names are stripped, nonempty and unique ignoring case, with a 120-character limit.
There is no profile deletion API. Only SHOW/HIDE are stored; DEFAULT removes a
known override. Ordinary save preserves stale stored keys, even when omitted by
the caller, and cannot introduce or alter an unknown key. Reset removes stale
keys too and keeps assignments.

The expected version covers both name and composition. Owner mutations lock the
profile, check its version and touch the aggregate so overrides-only saves and
empty resets also increment `@Version`. A competing editor receives
`NavigationConflictException`. Unique/FK constraints protect profile names,
profile/target overrides and user assignments. Concurrent changes of one user
assignment may also produce a conflict; reload before retrying. Operations join an
existing transaction and roll back with it. Public APIs expose detached records,
not entities or repositories.

`NavigationProfileReader.currentProfile()` reads only the current user's
assignment, without administrator access. Empty means no assignment. Authentication
and persistence failures propagate and must not be converted to developer defaults.
Reads started by the module use repeatable-read isolation for a consistent profile
snapshot; an ambient transaction keeps its caller's isolation. There is no user cache.

PostgreSQL additive DDL ships at `db/navigation-postgresql.sql`. Apply it once to
the pilot database before setting the flag, then start with
`spring.jpa.hibernate.ddl-auto=validate`. It does not modify existing tables or
add a migration framework. Automated H2 tests use generated disposable schemas;
`scripts/test-navigation-postgresql.ps1` at the repository root creates an isolated
local PostgreSQL database, applies the shipped DDL, validates and restarts the
context, then removes only that generated database. The PostgreSQL test is skipped
in ordinary builds unless that script supplies its environment.

KI-3 connects the application's menu and subsystem tiles through a UI-scoped
snapshot. It reads the assigned profile once when the shell builds its menu and
resolves it with the existing RlsReadGate. Tiles use that same resolved node;
FormNavigator still opens lists and enforces their normal access checks. A profile
read failure shows an error in the dynamic menu and keeps fixed service entries.
Refresh or a new login applies settings; already opened tabs remain open.
With platform.navigation.enabled=false the application retains its legacy path.
KI-4 adds the application's "Интерфейс" administration tab when the feature is
enabled. It uses the full catalogue, three-state overrides and the same resolver
for a preview before user read permissions. Changes stay local until save;
conflicts retain the draft. Creation, renaming, reset and assignment operations
use NavigationAdministrationService; user search remains bounded canonical lookup.
Stale keys are read-only in the editor and removed only on reset. The module
still has no Vaadin dependency. Browser pilot acceptance belongs to KI-5.
