# E0 — общие контракты G1 extension

Дата: 2026-10-29. Результат: **E0 выполнен**.
Baseline: [контракты](../../../entity-generation-g1-extension-contracts.md).

Опубликованы recipe-aware inherited/base/publication API, общий EntityJavaGenerator
с сохранением catalog-v2 поведения и GenerationManifest v2 с единым hash алгоритмом.
Owner/JSON v2, C summary/state v2, независимые сценарии и ownership файлов
зафиксированы для последующих реализаций. Document draft пока HANDOFF_ONLY.

Проверка выполнена на JDK 21, offline Maven, одним reactor core + json без install
в общий Maven cache. Reactor pom и рабочий log:
`outputs/entity-generation-g1-extension/e0/20261029/`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
& 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd' `
  -o -f outputs/entity-generation-g1-extension/e0/20261029/pom.xml test
```

Для воспроизведения reactor pom имеет modules `../../../../entity-design-core` и
`../../../../entity-design-json`, packaging=pom; дополнительных dependencies нет.

| Suite | Tests | Failures/errors/skips |
| --- | ---: | --- |
| DraftOperationsTest | 7 | 0/0/0 |
| DraftValidatorTest | 43 | 0/0/0 |
| CatalogJavaGeneratorTest | 14 | 0/0/0 |
| GenerationManifestTest | 1 | 0/0/0 |
| LegacyContextBinaryCompatibilityTest | 1 | 0/0/0 |
| RecipeContractTest | 2 | 0/0/0 |
| RecipeHandoffTest | 8 | 0/0/0 |
| DraftJsonCodecTest | 9 | 0/0/0 |
| Всего | 85 | 0/0/0 |

Это уникальные tests последнего прогона, повторы не суммируются. XML отчёты
сохранены в [surefire](surefire/), [полный build log](core-json-tests.log).
Manifest test включает portable golden hash, UTF-8 byte length, JSON roundtrip,
missing/extra/duplicate/reordered/empty set, digest mismatch, altered content,
byte count mismatch, changed plan/profile и case-insensitive path collision.
Dispatcher test сверяет прежний catalog plan и stale диагностику.
Старые golden Java fixtures, JSON fixtures и binary context compatibility проходят.

Source fingerprint core обновлён и независимо перепроверен:
`gitvaa-source-tree-sha256-v2`, 48 files,
`9efd010f1a00fcf394d8653ce363302290d5e7686c179d700e83d91e35e6fc3a`.
Изменены только core entry hash/count в существующем dependency manifest.

UI, browser, actual generated document C/D, multi-file filesystem apply и migration
не выполнялись и этим evidence не закрываются. C/D v1 продолжают использовать
свои прежние форматы; подключение v2 consumers относится к E4.C0/E4.D0.
