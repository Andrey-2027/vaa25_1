# entity-design-source

Reads and changes the Java file of an existing entity. The file stays the source of truth: nothing is generated
over it, a change is a small patch of the file and the SQL for the database is a separate script that you run yourself.

The module needs a JDK (not a JRE) and depends on `entity-design-core` only. It has no UI, persistence or Jackson dependencies.

## What it can change

| Request | In the file | SQL |
| --- | --- | --- |
| add a field (`STRING`, `INTEGER`, `LONG`, `DECIMAL`, `BOOLEAN`, `DATE`, `DATETIME`, `REFERENCE`) | the field, its getter and setter, missing imports, the columns of the list and selection when they are listed by hand | `ALTER TABLE ... ADD COLUMN`, for a reference also `ADD CONSTRAINT ... FOREIGN KEY` |
| change the label of a field | the `label` of its `@FieldMetadata` | none |

Everything else (removing or renaming a field, another type, length, column, order, a mandatory field, registers, table
sections) is refused with the property it concerns. Only a class that reaches `BaseEntity` through its base classes can be changed.
A field whose annotations are not plain literals (a constant as the label, an enum, a collection, several names in one declaration)
is *locked*: it is left alone and its name and column stay reserved.

## Commands

Through the script (finds Java and Maven from `JAVA_HOME`, `MAVEN_HOME` and `PATH`, builds the tool on first use,
asks Maven for the class path of the project):

```powershell
.\scripts\entity-modify.ps1 plan -Project C:\work\consumer -Type com.example.entitytypes.model.CustomCatalog `
    -AddField 'name=address,label=Адрес,type=STRING,length=200' -Out C:\work\plan1
.\scripts\entity-modify.ps1 apply -Project C:\work\consumer -Plan C:\work\plan1
```

Without the script (any system with a JDK 21; this is all that is needed on Linux). First a file with the class path of the project:

```bash
mvn -q dependency:build-classpath -Dmdep.outputFile=classpath.txt
java -cp entity-design-source/target/classes:entity-design-core/target/classes org.ipro.design.source.EntityModifyCli \
    plan --project . --type com.example.entitytypes.model.CustomCatalog \
    --add-field 'name=address,label=Адрес,type=STRING,length=200' --classpath-file classpath.txt --out ../plan1
java -cp entity-design-source/target/classes:entity-design-core/target/classes org.ipro.design.source.EntityModifyCli \
    apply --project . --plan ../plan1
```

On Windows the class path entries are separated by `;`, not `:`. A label with non-Latin letters is passed in the code page of the
system, so the letters must be in it (Russian Windows: cp1251 is fine).

`plan` prints the diff and the SQL and writes the plan directory; it never touches the project. `apply` replaces the file
only if it is still the one the plan was made from, and says where `schema.sql` is.

Options of `plan`: `--project`, `--type`, `--out`, `--classpath-file`, `--add-field` and `--set-label` (each may repeat),
`--source-root` (default `<project>/src/main/java`). A field is `name=value` pairs separated by commas (write `\,` for a comma in a value):
`name`, `label`, `type`, `length`, `precision`, `scale`, `column`, `reference` (the class a `REFERENCE` points to),
`form`, `list`, `selection` (`true`/`false`, all `true` unless given), `required` (`AUTO`/`REQUIRED`/`OPTIONAL`).
A label is changed with `--set-label <field>=<new label>`.

| Exit code | Meaning |
| --- | --- |
| 0 | done (`plan` written, `apply` applied, or the file already has the planned content) |
| 1 | wrong usage |
| 2 | a file changed after the plan was made: make a new plan |
| 3 | refused: the change is not supported, the file does not compile, the plan directory was altered, the file cannot be replaced |

## The plan directory

### Legacy form-column migration

```powershell
.\scripts\entity-modify.ps1 migrate-forms -Project C:\work\consumer -Type com.example.model.Material -Out C:\work\forms-plan
.\scripts\entity-modify.ps1 apply -Project C:\work\consumer -Plan C:\work\forms-plan
```

`migrate-forms` only writes a plan. It transfers literal `listColumns` / `selectColumns` from
`@EntityMetadata` to `@ListColumns` / `@SelectionColumns` in an existing configuration bound by
`@FormConfigurationFor`, or creates `<Entity>FormConfig` in the entity package. Explicit lists stay
explicit, including those matching automatic columns. Legacy selection fallback is materialized when
necessary to preserve its columns. Empty legacy arrays remain automatic. There is no AUTO conversion option.
Other configuration annotations, column properties and method bodies are preserved; conflicting defaults,
multiple destination configurations, unsupported expressions and occupied destinations are refused.

This command does not need the consumer classpath. Its plan contains `changes.diff` and
`form-migration.properties` (paths, hashes, original/new UTF-8 contents encoded as Base64, and a manifest
fingerprint). `apply` checks both files, every destination and all base hashes before writing. It also
rebuilds the migration plan to detect changed bindings or composition. All replacements are staged first;
any caught write/verification failure restores existing files and removes files created by this apply.
If restoration itself fails, the error identifies the files to restore from the manifest. A fully applied
plan is a no-op; a partially applied or changed plan is a conflict. There are no database changes.

Migration is parsed and checked by the static form reader, without compiling the consumer. Build the
consumer after applying. As with the single-file command, concurrent writes after the final check and
abrupt process/machine termination are outside the rollback guarantee.

### Entity modification plan

| File | Content |
| --- | --- |
| `changes.diff` | the change as a unified diff, for reading |
| `schema.sql` | one statement per line; empty when only a label changes |
| `plan.properties` | plan id, the changes, the files the plan depends on and their hashes, warnings |
| `base.java` | the whole file before the change; copy it back to undo |
| `new.java` | the whole file after the change |

`apply` reads the directory back and refuses it if anything was changed by hand: the hashes of both Java files, the plan id,
the diff and the script must all match.

## The SQL

`ALTER TABLE <table> ADD COLUMN <column> <type>;` with `VARCHAR(n)`, `INTEGER`, `BIGINT`, `NUMERIC(p,s)`, `BOOLEAN`, `DATE`,
`TIMESTAMP(6)`. The script is for a database that does not have the column yet and is run **before** the updated application
starts. It has no `IF NOT EXISTS`, so running it twice fails instead of skipping. An application with `ddl-auto=update` does not need it.
When the table or the target of a reference cannot be told from the sources (`@Table(schema)`, `@SecondaryTable`, table inheritance,
a target with its own `id`), the plan is refused with `SCHEMA_UNKNOWN`.

## What is checked, and what is not

* The patched file is parsed again and compiled alone against the class path of the project, with the project sources on the source path.
* Bytes outside the patch are checked to be unchanged.
* Before the write: the file and every file the plan depends on (base classes, known subclasses, the target of a new reference) must have the
  hashes of the plan. The new text goes to a temporary file in the same directory and replaces the old one in one atomic move; the result
  is checked and the old bytes are put back if it is wrong.
* **Not checked:** a new subclass written after the plan was made, and another program writing the file between the last check and the move.
  Build the whole project after `apply`; that is the check that counts.
* A file open in another program on Windows cannot be replaced; `apply` says so and leaves the file whole.
