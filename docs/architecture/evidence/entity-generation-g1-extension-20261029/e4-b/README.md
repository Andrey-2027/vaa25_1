# E4.B — P8 preview/download комплекта Java

Исторический partial result. Последующее завершение на генераторе v4, actual P8
downloads и стандартных формах с H2: [E4.B completion](../e4-b-completion/README.md).
Приведённые ниже 24 tests и ограничения относятся только к первоначальному прогону.

Дата: 2026-10-30. Это **часть** E4.B из
[плана §11](../../../../plans/entity-generation-g1-extension-plan.md): UI/IO-выдача уже
построенного reviewed `GenerationPlan`. Actual N-file generator E4.A, section-формы,
browser и C/D integration не входят в этот прогон. Контракт ZIP —
[E0.4](../../../entity-generation-g1-extension-contracts.md#zip-и-manifest-файл).

## Результат

P8 теперь принимает план с 1..N файлами, не меняя v3-генератор:

- Preview показывает operation и relative path каждого entry, счётчик `Файлов: N`,
  путь выбранного файла и его точный текст. Переключение entry не меняет draft.
- Один файл по-прежнему скачивается как `<Class>.java` с прежним content type;
  N>1 скачивается как `<Class>-java.zip`.
- ZIP содержит ровно корневой `generation-manifest.json` и все Java entries по
  relativePath. Manifest — JSON `GenerationManifest`, UTF-8 без BOM, без CR,
  ровно с одним завершающим LF. Java entries совпадают с планом byte-for-byte.
- Freshness/access проверяются на весь `GenerationPlan` внутри UI-lock: identity
  generation, revision, context/draft applicability, generator version, epoch и
  access. Поздний callback после любой правки получает 409 и не пишет bytes;
  отказ доступа остаётся 403.
- Preview/download не вызывают JSON checkpoint: draft/revision не меняются,
  dirty сохраняется. Статус «Комплект Java сформирован» не означает C/D.

Изменения:

- `src/main/java/org/ip/views/admin/JavaBundleArchive.java` — IO/UI-адаптер ZIP и
  single-file bytes; core не пишет файлы.
- `src/main/java/org/ip/views/admin/EntityDraftEditorView.java` — список entry,
  выбор файла, счётчик, `download` и ZIP delivery.
- `src/main/java/org/ip/views/admin/DraftEditorSession.java` — package-private seam
  `previewJava(GenerationPlan)` для component test до появления E4.A.
- `src/test/java/org/ip/views/admin/EntityDraftJavaBundleTest.java` — 3 новых теста.

## Прогон

Перед прогоном локальные SNAPSHOT `entity-design-core`, `entity-design-json`,
`entity-design-runtime` и `platform-vaadin` пересобраны из текущих исходников:
приложение уже использует `OwnerDraft` и `catalogOwner`, а установленные ранее jars
были старше этих исходников. Это подготовка test environment, не изменение E4.B.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
& 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -o -B -ntp "-Dmaven.repo.local=C:\Users\strim\.m2\repository" `
  "-Dvaadin.skip=true" `
  "-Dtest=EntityDraftJavaBundleTest,EntityDraftJavaPreviewTest,EntityDraftJavaDownloadTest,EntityDraftDocumentTest,EntityDraftMatrixScenarioTest" `
  test
```

Результат: **24 tests, 0 failures, 0 errors, 0 skipped**, `BUILD SUCCESS`.

| Suite | Tests | Результат |
| --- | ---: | --- |
| `EntityDraftJavaBundleTest` | 3 | preview list/selection/count, ZIP+manifest, stale multi plan → 409 |
| `EntityDraftJavaPreviewTest` | 7 | single-file preview и diagnostics regression |
| `EntityDraftJavaDownloadTest` | 8 | exact UTF-8/LF, freshness/access, dirty/checkpoint |
| `EntityDraftDocumentTest` | 5 | document v3 и single-file delivery regression |
| `EntityDraftMatrixScenarioTest` | 1 | реальный imported fixture → preview → download |

ZIP-проба проверяет ровно три entry (`generation-manifest.json`,
`org/example/MaterialCard.java`, `org/example/MaterialCardRow.java`), совпадение
содержимого и `sha256` второго entry с планом, полный набор полей
`GenerationManifest` (`kind`, `planId`, `recipe`, `generatorVersion`,
`fingerprintProfile`, `entries`, `manifestSha256`), `entries=2`, отсутствие BOM/CR
и ровно один финальный LF. Single-file regression в существующих тестах подтверждает
прежний `.java` download без ZIP.

## Артефакты

- [test-results.json](test-results.json)
- [surefire XML](reports/) — пять suite reports финального прогона.

## Границы

- Multi plan в тесте synthetic и подставлен package-private seam; `EntityJavaGenerator`
  остаётся v3 и по-прежнему выдаёт один файл.
- Не проверены 0/1/2 реальные секции, section metadata, standard forms и browser:
  это зависит от E4.A и остаётся открытым вместе с actual A→C→D.
- ZIP проверен на component/IO уровне; D apply, SQL, migration и deployment не
  запускались. Полный E4 этой работой не закрыт.
