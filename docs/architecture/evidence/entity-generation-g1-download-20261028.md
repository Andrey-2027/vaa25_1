# G1.1.6: скачивание Java и evidence

Дата: 2026-10-28. Объём: G1.1.6 из [плана](../../plans/entity-generation-g1-minimum-plan.md).

В footer открытого Java preview добавлена ссылка «Скачать Java». Она выдаёт ровно файл текущего
плана в UTF-8/LF, с именем класса и расширением `.java`, MIME `text/x-java-source; charset=UTF-8`
и `Cache-Control: no-store`. Рабочий source checkout и схема не изменяются.

## Актуальность и доступ

DownloadHandler получает текущий context через существующий catalog source и проверяет:

- текущий UI-допуск, владельца и epoch документа;
- тот же открытый preview и его download token;
- ту же revision и тот же текущий план session;
- `GenerationPlan.appliesTo` для полного draft/context;
- текущую версию генератора и один файл плана.

Несовпадение epoch/доступа даёт HTTP 403, устаревший/закрытый preview — HTTP 409.
Ни в одном случае отказа output stream не открывается. Даже изменение с возвратом прежнего текста
требует нового preview; равенство snapshot само по себе не восстанавливает старую ссылку.
Замена preview, закрытие, detach, импорт и новый документ отзывают download token.

Для одного ограниченного source-файла проверка и выдача выполняются внутри `UI.accessSynchronously`:
редактирование/import этой session не вклиниваются между проверкой и записью. Это сознательное
удержание session lock на время выдачи одного файла; общего механизма фоновой записи не добавлено.
IOException передаётся вызывающему коду, текущий preview допускает повторное скачивание.
Ни успех, ни сбой Java download не вызывают `session.exported` и не меняют JSON checkpoint/dirty.

## Проверка и воспроизведение

```powershell
./scripts/test-entity-generation-g1-editor.ps1 -TestJvmArguments '-javaagent:C:/Users/strim/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar'
```

Новый `EntityDraftJavaDownloadTest` проверяет реальные DownloadHandler callbacks, UTF-8 bytes/LF,
имя/MIME/cache, выдачу внутри UI callback, изменения draft и полного context при том же version label,
импорт с прежним UUID, новый документ, смену principal/прав, закрытие/замену/detach preview,
сбой output stream, повтор и независимость от JSON checkpoint.

Тестовый UI теперь имеет реальный StreamResourceRegistry в mock VaadinSession; проверяется наличие
зарегистрированной download resource. Первый прогон без этого registry выявил ошибку тестовой
обвязки при attach ссылки; лог сохранён в `outputs/entity-generation-g1/g1-1/ui-tests-download-initial.log`.
Код ядра и dependency fingerprints в этом шаге не менялись.

Окончательный прогон: **56 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS.
Из них 8 — download tests, 7 — preview tests. Это один прогон, без суммирования повторных запусков.
Компиляция приложения и тестов прошла на Java 21; это не проверка компиляции созданного класса.

## Evidence

Исходная fixture хранится в `src/test/resources/org/ip/views/admin/fixtures/entity-generation-g1.json`:
стабильные UUID, catalog-v2, все семь scalar types, required/optional, разные form/list/selection,
явная колонка, STRING length и DECIMAL precision/scale, кириллица/кавычки/backslash/newline.

Тест импортирует fixture в настоящий P8 editor, открывает preview, вызывает настоящий download handler
и сохраняет результат в `outputs/entity-generation-g1/g1-1/download/`. Повторный preview сравнивается
с полным исходным планом; SHA-256 вычисляется по фактически выданным bytes.
`plan.json` — evidence-сериализация существующего immutable плана с полным source/context,
а не новый пользовательский формат импорта или файловый adapter.

Закреплённые копии артефактов и результаты последнего успешного прогона — в
[entity-generation-g1-1](entity-generation-g1-1/).

План: `g1-4be5c34e3038623c8a933ef9eff32c47f08db35529eb0da8f4106f41fb47088e`.
Recipe: `standard-catalog-v2@2`, generator: `catalog-java-v1`.
Выдано 3645 bytes; SHA-256 Java:
`21ab6d0c6158b2a440e81965def2a0b67593a48a56a520d69a6395c32abcfff8`.
После копирования отдельно проверены совпадение хэша файла с plan.files и совпадение текста плана
с сохранёнными UTF-8 bytes/LF. G1.1 выполнен; `compiled=false` сохранён в delivery evidence.

Нативный браузерный прогон не выполнялся. Эта приёмка относится к preview/download;
компиляция, startup и persistence именно созданного класса относятся к G1.2,
CREATE-only применение и migration plan — к G1.3.
