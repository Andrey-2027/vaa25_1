# G1.1.5: Java preview в редакторе P8

Последующее обновление: G1.1.6 выполнен; [приёмка download и закреплённые артефакты](entity-generation-g1-download-20261028.md).
Ниже сохранены результаты шага G1.1.5; общая output папка содержит уже более поздний прогон.

Дата: 2026-10-28. Объём: G1.1.5 из [плана](../../plans/entity-generation-g1-minimum-plan.md).

## Поведение

В toolbar существующего `EntityDraftEditorView` добавлена кнопка «Показать Java».
Действие повторно проверяет UI-доступ и epoch документа, получает текущий project context,
проверяет текущий draft штатным `DraftValidator` и передаёт CheckedStructure в настоящий
`CatalogJavaGenerator` из потока A. Поля редактора уже используют EAGER value change mode;
предварительная выгрузка JSON не требуется. Импортированный draft проходит тот же путь.

Успешный результат показывается в Dialog: имя класса, относительный путь и Java-текст в
read-only TextArea. Пользовательский текст не вставляется как HTML. Статус явно различает
формирование исходника и ещё не выполненную проверку компиляцией.

Ошибки структуры остаются в существующей панели с открытым списком проблем. Generation diagnostics
показываются отдельно как ограничения первого генератора, с адресными переходами к свойству,
полю или табчасти. Навигация переиспользует существующий DraftProblem.Target; второй валидатор
и временный генератор не добавлены.

Результат принадлежит одной editor session. Изменение draft/context, импорт и новый документ
сбрасывают его. Открытый preview закрывается при инвалидировании, смене владельца, отзыве доступа
и detach редактора. Запоздалая кнопка другого epoch и старые ссылки diagnostics не применяются
к новому документу. Preview не меняет draft, business data, revision, dirty или JSON checkpoint.
Общий `RecipeCatalog.generationEnabled` не изменён.

## Интеграция и проверка

Ядро потока A не изменялось. Его фактический прогон дал 73 tests, 0 failures/errors/skips.
В sandbox фаза install не смогла записать общий Maven cache; после успешных tests повторён
только install с `-DskipTests` и разрешённым доступом. Оба лога сохранены:
`outputs/entity-generation-g1/g1-1/core-integration.log` и `core-install.log`.
В manifest обновлены только fingerprint и описание `entity-design-core`:
44 файла, `b52d81c5d2a39adef09743111c268f4c89d81679f46b27e8d8b0891cb283dc4a`.

App-side воспроизведение:

```powershell
./scripts/test-entity-generation-g1-editor.ps1 -TestJvmArguments '-javaagent:C:/Users/strim/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar'
```

Сборка использует установленное актуальное ядро и изолированный `build-ui`, без очистки root target.
`EntityDraftJavaPreviewTest` проверяет настоящий Java-текст, read-only Dialog, сохранность dirty/checkpoint,
незавершённый ввод и UNKNOWN, импорт v2, отказ v1, адресные переходы к табчасти/полю,
неприменимость старых ссылок и проверок, обновление каталога и отзыв доступа.
Первый прогон новых тестов выявил две ошибки тестовой обвязки (footer кнопки Dialog и слабая ссылка
CurrentInstance на UI); они исправлены. Лог первой попытки: `ui-tests-first-preview-run.log`.
Отдельная попытка исправить поиск footer кнопки не скомпилировалась: DialogFooter не является Component;
тест закрытия переведён на `Dialog.close()`, проверяя его opened-change cleanup.
Этот лог сохранён в `ui-tests-footer-compilation.log`.
Окончательный прогон: **48 tests, 0 failures, 0 errors, 0 skipped**, BUILD SUCCESS;
из них 7 — `EntityDraftJavaPreviewTest`. Компиляция приложения и тестов прошла на Java 21.
Результаты хранятся в `ui-test-results.json` и `ui-tests.log` той же output папки.

Нативная браузерная проверка в этом шаге не выполнялась. G1.1.6 (скачивание Java и его freshness gate),
compile/startup/persistence G1.2 и применение G1.3 не входят в выполненный шаг.
Полный G1.1 пока не объявляется завершённым.
