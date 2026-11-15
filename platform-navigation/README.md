# Navigation backend

Enable the module with `platform.navigation.enabled=true`. The application
supplies `NavigationFunctions`, the subject areas, an administration access
check, and a user directory with immutable user keys.

## Menus and assignments

Startup creates one «Основное меню» from subject areas containing base list
functions. Other menus can be created empty or copied from any existing menu.
A copy preserves section and entry keys, area links and importance. Subsequent
edits affect the selected menu. Section keys, entry keys and area links are
unique within each menu.

Each user has at most one personal assignment. Assigning a different menu
replaces it. Without an assignment, the main menu applies. Deleting a named
menu removes its assignments. The main menu cannot be deleted.

`NavigationMenuReader.currentMenu()` reads the assignment and its structure in
one repeatable-read transaction. The application resolves the catalog once per
UI. A placement is visible when its function is available to the current user.
The resolver hides empty sections. The application checks availability again
before executing a function.

The startup reconciliation records when each function first appears. A newly
discovered base list is appended to the linked section of each menu, if there
is one. Existing and removed placements are left as configured. Variants and
commands are placed explicitly. Missing functions remain in saved structures
and are omitted from the resolved menu.

## Administration

Every `NavigationMenuService` operation checks administration access. Structure
saves use the menu version and commit atomically. Section and entry audit fields
do not constitute an audit trail. `importance` is stored per entry and defaults
to `NORMAL`. The editor and its display of importance are introduced in SM-3.

## PostgreSQL

`db/navigation-postgresql.sql` describes the SM-1b schema for a new database.
For the old pilot schema, stop the application and apply
`scripts/navigation/sm1b-reset.sql` before starting SM-1b with
`spring.jpa.hibernate.ddl-auto=update`. Check the reset on a copy of DBVaa25
first. The reset discards pilot menus, profiles, and assignments.
Startup reconstructs the main menu and retains the known-function table.

`scripts/test-navigation-postgresql.ps1` verifies the schema and restart
behavior in a disposable database.

## Menu transfer

The menu editor exports the saved menu as a version 1
`gitvaa.navigation-menu` JSON document. The file carries placement keys,
ordering and importance. User assignments are stored separately.
Import creates a named menu or replaces the selected menu's structure.
The replacement uses the version shown in the preview and leaves assignments
attached to the selected menu. The preview lists unknown functions whose
placements will be skipped.
