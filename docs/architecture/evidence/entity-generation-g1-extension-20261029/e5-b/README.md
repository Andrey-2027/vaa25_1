# E5.B — браузерная приёмка, 2026-10-31

Статус: **PASS, E5.B принят; E5.H + E5.B завершены. Migration gate M не запускался.**

Baseline: `outputs/entity-generation-g1-extension/e5-h/e5h-20261031`.
Использованы его isolated Maven repository и baseline 18 собранных модулей
(в runtime classpath стенда — 16 JAR из этого набора; BOM задаёт версии,
platform-navigation для изолированных routes не требуется),
скомпилированный P8 и 13 точных Java-файлов семи D-applied generated моделей.
Перед Prepare/Start и при сохранении evidence проверены SHA-256 артефактов E5.H;
generated Java и классы P8 дополнительно сверены с baseline при архивировании.
`e5h-artifact-bindings.json`, `consumed-dependencies.json`, `stand/*/generated-bindings.json`
фиксируют фактически потреблённый набор. Platform artifacts не пересобирались.

Стенды: `e5b-20261031` (127.0.0.1:8096, основной),
`e5b-standard-final-20261031` (127.0.0.1:8098, стандартный список/выбор).
У каждого процесса отдельная H2 in-memory DB, `ddl-auto=create-drop`, loopback bind,
canonical write boundary включён. Основная БД и migration tools не использовались.
Учётки только тестовые: `e5-admin` / `e5-local-only` (ADMIN, read/update/delete grants)
и `e5-reader` / `e5-local-only` (USER, только read grants), восемь типов с grants.

| Пункт E5.B | Наблюдавшийся результат | Evidence в `browser/` |
|---|---|---|
| 1 — изолированный consumer/P8/grants | Вход администратора; реальные metadata, EntityDataAccess, ItemForm, FieldFactory, TableSectionFactory и aggregate save adapter | 01; `stand/` |
| 2 — P8 документ | Новый документ, inherited number/date; scalar comment и reference counterparty; генерация, preview и одиночный download | 23, 25; `downloads/BrowserGeneratedDocument.*` |
| 2 — адресный отказ | Пользовательское поле `number` отвергнуто как конфликт стандартного поля/SQL имени; переход к диагностике и исправление на `comment` | 24 |
| 2 — комплекты | DocumentSections2 и OwnedCatalogSections2: корень, Row1, Row2; три пути и ZIP | 26–31; оба ZIP в `downloads/` |
| 3 — scalar document | Create, update и fresh reopen: String, Integer, Long, BigDecimal, Boolean, LocalDate, LocalDateTime; number/date | 02–03 |
| 3 — reference document | Required failure сохраняет номер; реальный SelectionForm A/B; required A→B, optional A→clear, save/reopen | 04–07 |
| 3 — reference catalog | Create/update имени; required reference B и очищенная optional reference после reopen | 08–09 (окончательный результат — 09) |
| 4 — owner create/reopen | Owner A/B подставлен при create; после save поле readonly и browse disabled; две разные записи | 12, 16–18 |
| 4 — стандартный список/выбор | Настоящие ListForm и SelectionForm для OwnedCatalog: контекст A показывает только A, B только B; callback выбора возвращает соответствующую запись | 33–37 |
| 4 — удаление используемого owner | Понятный отказ с OwnedCatalogSections2.counterparty и row2.target, запись сохранена | 13 |
| 5 — catalog sections | Две секции, add/edit/remove/save/fresh reopen; owner A и row references A/B в одном агрегате; ошибка required строки сохраняет диалог и корень | 10–15 |
| 5 — document sections | Две секции, add/edit/remove/save/fresh reopen; required failure сохраняет введённую ссылку и номер документа | 19–22 |
| Дополнительный UI access | e5-reader после входа получает явный отказ маршрута draft по annotations | 32 |

Сохранены **37 пар снимок + текст состояния** (DOM; для 32 — native AX).
Это checkpoints браузерного прогона, не число headless tests.
Кнопки списка в основном host используют небольшой служебный Grid;
они сами по себе не доказывают production owner filtering. Поэтому пункт 4
отдельно подтверждён неизменёнными стандартными ListForm/SelectionForm (33–37).
ItemForm и диалоги строк/ссылок во всех основных сценариях — стандартные компоненты.
Проверка выполнена в текущем desktop viewport; mobile/responsive acceptance в scope не входит.

`download-verification.json`: все **6 Java entries** двух browser ZIP совпали
побайтово с applied sources E5.H; manifest SHA-256/bytes, UTF-8 без BOM и LF проверены.
Одиночный BrowserGeneratedDocument.java совпал с прочитанным из preview исходником.
P8 использовал актуальный runtime snapshot стенда: planId новых browser downloads
может отличаться от E5.H. Эти выгрузки не выдаются за новый C/D apply; Java baseline
и ранее принятые C/D подтверждены отдельно.

Промежуточный `e5b-standard-20261031` на 8097 сохранён в `stand/` как диагностический
запуск: host пытался seed агрегат с sections через прямой CREATE, canonical boundary
корректно отказал. Финальный host использует OwnedCatalog для проверки списка/выбора.
Это исправление тестовой оболочки, не изменение platform реализации. Конфликт
JSESSIONID между loopback портами устранён остановкой старых стендов; launcher теперь
задаёт уникальное имя session cookie по RunId. Ошибки этого промежуточного запуска
не объявляются успешными сценариями. Итоговый standard-final server log без ERROR.

Ввод обычных Vaadin TextField проверялся native AX setValue с отдельным save/reopen:
ранние fill-only действия не служат доказательством сохранения; окончательные
checkpoints 03, 07, 09, 12, 15, 20–22 фиксируют повторно прочитанные данные.
UI access и этот браузерный результат не заменяют direct canonical denial suites E5.H.
Исключение register/CTE overlay из E5.H остаётся явно описанным в его evidence.

Повторный запуск из корня репозитория (PowerShell; первый запуск может загружать зависимости):

```powershell
./scripts/run-entity-generation-g1-browser.ps1 -Action Prepare -RunId e5b-review -Port 8096
./scripts/run-entity-generation-g1-browser.ps1 -Action Start -RunId e5b-review -Port 8096
# / — карточки; /draft — P8; /standard — стандартный owner list/selection
# Для /standard нажать «Создать тестовые записи A/B» в чистой H2 DB.
./scripts/verify-entity-generation-g1-browser-downloads.ps1
./scripts/run-entity-generation-g1-browser.ps1 -Action Stop -RunId e5b-review
```

Исходники host/launcher/verifier сохранены в `source/`, параметры и build/server logs
в `stand/`. `evidence-files.json` содержит размеры и SHA-256 архивных файлов.
Основной и промежуточный стенды остановлены после фиксации; финальный 8098 оставлен
для просмотра. H2 данные существуют только до остановки процесса.
