# E3.A — owner в draft/JSON/generation/projection

Дата: 2026-10-29. E3.A выполнен. E3.B (owner UI) и E3.C (actual owner C2/D2/runtime)
остаются открытыми; gate E3 целиком не закрыт. Предпосылка — публичный API E3.R:
CatalogOwner, resolved descriptor и portable TypeFacts.owner уже существуют.

## Реализация

- Nullable `OwnerDraft` добавлен отдельным компонентом EntityDraft; старый
  шестипараметрический constructor сохранён. Копии и структурные операции сохраняют
  owner. Нет UUID и дублирования владельца в subject fields.
- Validator разрешает owner только standard-catalog-v2@2, с подтверждённым existing
  managed Long-ID target и TypeFacts.kind=CATALOG. Неизвестный kind, incomplete
  snapshot, недопустимый target, self/new-root/row отклоняются. Ошибки имён,
  подписи, SQL column и collisions адресованы owner.* с fieldId/sectionId=null.
  Проверяются также accessor collisions с builtins/system/subject и getClass.
- `CheckedStructure.Owner` хранит resolved column, label, order=30, applications=root().
  `Projection.owner` содержит отдельный обязательный OwnerBinding. Owner публикуется
  после code/name в list/select и в form на позиции 30; subject fields получают 40+.
  Без owner порядок subject fields остаётся 30+.
- `catalog-java-v3` генерирует `@CatalogOwner(property="counterparty")`, NotNull,
  LAZY ManyToOne(optional=false), JoinColumn(nullable=false), FieldMetadata REQUIRED.
  Cascade, scalar constraints на owner и статический readOnly отсутствуют. Java
  target type задан полным source name; declaration/accessors владельца идут первыми.
- Отсутствующий/неверный OwnerBinding отклоняется до source output; публичный
  generator преобразует ошибку projection в PROJECTION_UNREPRESENTABLE на owner.
- F2 включает raw owner, resolved checked owner, binding и portable owner facts.
  Даже одинаковые bytes при AUTO/EXPLICIT column или изменении portable owner facts
  дают разные planId. Freshness проверяет весь draft/context. Сериализация F1
  сохранена; legacy builder явно отклоняет OWNER/ENVELOPE вне своего профиля.
- Отдельная `entity-draft-v2.schema.json` требует owner=null либо объект с четырьмя
  обязательными свойствами. V1 schema не изменена; codec v1 не выдаёт owner=null,
  а non-null owner явно отклоняет. Никакого автоматического upgrade нет. V2 сохраняет
  raw/незавершённый ввод, UUID и AUTO/EXPLICIT. Document v2 с owner=null поддержан
  document-java-v3; non-null owner экспортируется как invalid input и получает
  OWNER_UNSUPPORTED_RECIPE в semantic validator.

## Проверки

Итоговый regression run:
`outputs/entity-generation-g1-extension/e1-ab/e3a-owner-r4/`.

| Проверка | Результат |
| --- | --- |
| Core/JSON/runtime bridge/P8 regression | 26 suites, 182 tests, failures/errors/skips=0 |
| Owner generation/validator/projection | 10 tests, входят в 182 |
| Owner JSON | 5 tests, входят в 182 |
| Runtime owner bridge | 3 tests, входят в 182; API E3.R использован без повторной реализации |
| Legacy binary clients | 2 tests, входят в 182; старые context и шестипараметрический EntityDraft clients |
| Existing legacy golden/plan tests | Пройдены в основном run; F1 и scalar Java bytes сохранены |
| Source contract checks | 33/33, отдельно, без package/install |
| Actual OwnedCatalog.java | javac --release 21, exitCode=0 |

Javac проверяет выданный source с существующими platform/target JAR и текущим
исходником аннотации CatalogOwner из platform-contracts. Это compile-проверка E3.A,
не C2/runtime acceptance E3.C. Обязательность/immutability owner на сервере и UI
не объявляются проверенными этим javac. Основное приложение owner-панель не получило.
Существующие P8 tests — regression текущего preview/import/export, не приёмка E3.B.

Shared Maven SNAPSHOT cache не обновлялся. Новые tests не вводят runtime/UI/JPA
зависимостей в core или JSON; current runtime adapter потребляется через reactor.
182 — один итоговый run; ранние повторения и отдельный javac к нему не прибавляются.

## Evidence и воспроизведение

- [Результаты tests](test-results.json), [reactor log](tests.log), [XML](reports/).
- [Actual owner input/plan/manifest/Java](owner-input/).
- [Javac result](compile-result.json), [log](javac.log), [проверки source binding](verification.json).
- [Contract checks](contract-results.json), [актуальные source fingerprints](source-fingerprints.json).

```powershell
pwsh -NoProfile -File scripts/test-entity-generation-g1-document.ps1 -RunId <new-e3a-run>
pwsh -NoProfile -File scripts/test-entity-design-contract.ps1 -SkipBuild -OutputDirectory <new-output>
```

Regression launcher сохраняет историческое имя document/E1, но включает owner/core,
JSON, runtime bridge и P8 tests. Owner export лежит в `<run>/owner-input/`; остальные
document/reference exports остаются раздельными. Полный owner route C2/D2 выполняется
следующим пакетом E3.C с независимыми owner runtime expectations.
