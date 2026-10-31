# E2 — ссылки на существующие сущности

Дата: 2026-10-29. E2.A/B/C выполнены для catalog и document: одиночная optional и
одиночная required ссылка на существующий managed Long-ID root. Generated classes
не подключены к основному приложению. Owner/E3, sections/E4, browser/E5.B и migration
gate M остаются открытыми.

## Реализация

Текущий `EntityJavaGenerator` выдаёт `catalog-java-v2` / `document-java-v2`, F2/M2.
Обе ассоциации — `ManyToOne(fetch=LAZY)` с явным `JoinColumn`; required дополнительно
`optional=false`, `nullable=false`, `NotNull`. Cascade и scalar length/precision
для ссылок отсутствуют. Metadata выводит тип и lookup target по Java/JPA.

`ReferenceTargetDescriptor.sourceTypeName` приходит из canonical runtime type name,
валидируется в core и входит в freshness/F2. Старые context JSON и восьмиаргументный
constructor совместимы. Полные Java names разрешают одинаковые simple names и
коллизии imports. Подтверждённые facts, Long-ID, managed root, representation и
selection остаются условиями допуска; unknown/wrong-ID/row targets отклоняются.

P8 переиспользует существующие reference input/выбор и показывает текущий preview.
Импорт/экспорт draft, потеря target facts, инвалидирование плана и переход к полю
проверены компонентно. Формы проверены через настоящие `FieldFactory`, `EntityField`,
`FormBindingRegistry`, `LookupComboHelper`, `SelectionFormAssembler` и canonical
LIST read с column fetch paths. Отображение ссылки и чтение целевых полей проверены
после закрытия persistence context. Браузерный пользовательский маршрут не выполнялся.

Существующий target поставляется отдельным support JAR
`org.ipro:g1-reference-target:1.0-E2`. Он не является скрытым generated entry.
Профиль consumer `g1-references` подключает target и стандартные Vaadin components;
исходники тестов и target отделены от единственного generated source.

## Проверки

| Проверка | Результат |
| --- | --- |
| Core/JSON/runtime/P8 regression | 23 suites, 163 tests; failures/errors/skips = 0 |
| Source contract checks | 33/33 |
| D selftests | 66/66; missing/extra/unreadable dependencies, drift на NOOP и после публикации включены |
| ReferenceCatalog actual C2 | 6 tests; errors/failures/skips = 0 |
| ReferenceDocument actual C2 | 6 tests; errors/failures/skips = 0 |
| D2 обоих видов | APPLIED → NOOP; exact source bytes, receipt binding, journal отсутствует |
| Applied consumer runtime | По 6 tests для каждого вида; BUILD SUCCESS |
| Actual target JAR drift, facts/plan неизменны | REJECTED/DEPENDENCIES_CHANGED до CREATE и на NOOP для обоих видов |
| Legacy Matrix C1/D1 | C1 success, D1 APPLIED → NOOP; прежние F1/planId/Java bytes совпали |
| Legacy applied runtime | 5 GenerationScenarioTest + 3 MatrixRuntimeTest; BUILD SUCCESS |

Runtime включает canonical create/read/update, замену A→B, очистку optional,
required-null и missing-target отказы, grants для lookup/root write, отказ удаления
используемой цели и сохранность target при удалении ссылающейся записи. Проверено
сохранение/повторное открытие через стандартные bindings и восстановление lookup.

163 — размер одного итогового reactor run; повторные C/applied/runtime runs и D
selftests к нему не прибавляются. Legacy UI Matrix test отдельно не повторялся:
использован сохранённый actual P8 export E1, текущий D1 заново проверил его через
fixed legacy builder. Старые E1 evidence не перезаписаны.

Consumer dependency locations получены из собственного Maven resolution каждого
проекта до apply. C2 и D2 согласованы по всем 19 effective `org.ipro` JAR id/hash,
включая target и platform-vaadin; после runtime hashes пересчитаны снова.
Для drift менялись только копии target JAR в outputs. Platform SNAPSHOT JAR
не переустанавливались; установлен только новый отдельный support target artifact.
При drift после публикации D выполняет rollback и возвращает `ROLLED_BACK`, что
проверено selftest. Сохранность чужих/изменённых файлов покрыта D selftests.

## Evidence

- [67 binding/source/dependency checks](actual-verification.json), [проверенные D source hashes](d-source-hashes.json).
- [Core/P8 results](p8-test-results.json), [log](p8-tests.log), [app XML](p8-reports/), [core XML](core-reports/), [JSON XML](json-reports/).
- [Contract checks](contract-results.json), [D selftests](d-selftest-summary.json).
- [ReferenceCatalog](reference-catalog/): input/plan/manifest, C summary/reports,
  apply receipt, APPLIED/NOOP и drift результаты, applied runtime XML/log.
- [ReferenceDocument](reference-document/): тот же независимый маршрут.
- [Legacy Matrix](legacy/): C1, D1 и applied runtime XML/log.

Рабочий каталог: `outputs/entity-generation-g1-extension/e2/work/`.
Итоговый core/P8 run: `outputs/entity-generation-g1-extension/e1-ab/e2-references-r5/`.
Actual C runs: `work/c/reference-catalog-r9` и `work/c/reference-document-r9`.
Финальные applied consumers: `work/applied-current/reference-catalog` и
`work/applied-current/reference-document`; D selftests: `work/d-current-selftest`.
При сверке обнаружены параллельные multi-file изменения D: они сохранены, текущий
D скомпилирован в отдельный snapshot, 66 selftests и actual E2 маршруты повторены
на этой версии. Это regression E2; приёмка E4 этим не объявляется.
Ранние диагностические прогоны в outputs не являются дополнительными успешными tests.

## Воспроизведение

На JDK 21, PowerShell 7 и существующем offline platform cache:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Axiom/AxiomJDK-21'
$maven = 'C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd'
& $maven -o -f scripts/entity-generation-g1/reference-target/pom.xml install
pwsh -NoProfile -File scripts/test-entity-generation-g1-document.ps1 -RunId <new-run>
```

Последняя команда экспортирует оба reference plans в `<run>/reference-input/`.
Для каждого из них запустить `scripts/test-entity-generation-g1.ps1` с SourceFile,
RelativePath/ExpectedSha256 из manifest, ManifestFile и соответствующим
`scripts/entity-generation-g1/scenarios/reference-{catalog|document}/ScenarioSpec.java`.
Маркер REQUIRED_SUITE подключает `g1-references` и независимый ReferenceScenarioTest.

Новый applied project создать из `scripts/entity-generation-g1/consumer/`, положить
соответствующий ScenarioSpec и `reference-tests/ReferenceScenarioTest.java` в harness
test package. Получить его runtime tree командой Maven
`-o -Pg1-references generate-test-resources`. Применить через
`scripts/apply-entity-generation-g1.ps1 -Apply -ProjectRoot ... -CSummary ...
-ReviewedPlan ... -DependencyTree <consumer>/target/consumer-runtime-dependency-tree.json`
и повторить для NOOP. Выполнить Maven `-o -Pg1-references -Dtest=ReferenceScenarioTest test`
из applied project. Generated source вручную в consumer не копировать.

Selftests D: `pwsh -File scripts/apply-entity-generation-g1.ps1 -OutputDirectory <new-output>`.
Проверка filesystem publication требует окружения с разрешёнными hard links.
