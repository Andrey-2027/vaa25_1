# Navigation backend

## Optional navigation storage

The module is inert unless `platform.navigation.enabled=true`. When enabled, its
auto-configuration registers the catalog provider, internal JPA stores and repositories,
`NavigationAdministrationService`, `NavigationProfileReader` and the resolver.
It requires an initialized `SubsystemRegistry`, a `NavigationFunctions` bean, a transaction
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

The application runs with `spring.jpa.hibernate.ddl-auto=update`, which creates the navigation tables from the module's `@EntityScan`. PostgreSQL additive DDL ships at `db/navigation-postgresql.sql` and is the reference for `validate` mode and for the PostgreSQL test. Apply it once to
the pilot database before setting the flag, then start with
`spring.jpa.hibernate.ddl-auto=validate`. It does not modify existing tables or
add a migration framework. Automated H2 tests use generated disposable schemas;
`scripts/test-navigation-postgresql.ps1` at the repository root creates an isolated
local PostgreSQL database, applies the shipped DDL, validates and restarts the
context, then removes only that generated database. The PostgreSQL test is skipped
in ordinary builds unless that script supplies its environment.

KI-3 connects the application's menu and subsystem tiles through a UI-scoped
snapshot. The shell reads one catalog from storage and uses it both to classify
stale profile keys and to resolve the menu with the current user’s permissions. Tiles use that same resolved node;
FormNavigator still opens lists and enforces their normal access checks. A profile
read failure shows an error in the dynamic menu and keeps fixed service entries.
Refresh or a new login applies settings; already opened tabs remain open.
The application always enables the module (КИ-7): with `platform.navigation.enabled=false` it does not start, with a clear error.
KI-4 adds the application's "Интерфейс" administration tab, which is always present.
It uses the full catalogue, three-state overrides and the same resolver
for a preview of the layout and the current administrator’s command access. Changes stay local until save;
conflicts retain the draft. Creation, renaming, reset and assignment operations
use NavigationAdministrationService; user search remains bounded canonical lookup.
Stale keys are read-only in the editor and removed only on reset. The module
still has no Vaadin dependency. Browser pilot acceptance belongs to KI-5.

## Persisted menu (SM-1)

The application supplies the published list functions through `NavigationFunctions`.
The function index is cached after the form registry closes. Sections and entries
are read from the database for each new UI snapshot.

The first startup builds sections from areas with list functions in their subtree.
Later startups record newly discovered functions and append lists to the one
section linked to their area. Moving or deleting a placement preserves the
function's discovery record. A section can be linked to at most one area,
and an area can feed at most one section.

Profiles refer to placement keys `section:<id>` and `entry:<id>`. Moving an
entry keeps the profile key and creation audit fields. A function that disappears leaves a stale placement
in the structure and is omitted from the menu.

The function catalog contains base lists, named list variants, create commands and
application NAVIGATION actions. Initial assembly and startup reconciliation place
only base lists. Placed lists and variants require read access. Placed commands
require an explicit SHOW override and an allowed action decision. The application
checks the decision again when the user opens the function.
