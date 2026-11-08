# AF4.1 — новые рецепты и конфигурации форм

Дата: 07.11.2026, Europe/Moscow. Основание: основной план §6.1 и рабочий план
§1.1 / AF4.1. Полный прогон запрещён владельцем; выполнены только целевые проверки.

## Результат

| Рецепт | Предшественник | Генератор | Профиль |
| --- | --- | --- | --- |
| standard-catalog-v3@3 | standard-catalog-v2@2 | catalog-java-v5 | plan-fingerprint-v5 |
| standard-document-v2@2 | standard-document-v1@1 | document-java-v5 | plan-fingerprint-v5 |
| independent-register-v3@3 | independent-register-v1@1 | register-java-v3 | plan-fingerprint-v5 |
| independent-register-v4@4 | independent-register-v2@2 (RLS) | register-java-v4 | plan-fingerprint-v5 |

Обновление не происходит автоматически: JSON сохраняет выбранную точную версию.
Новые рецепты используют прежние форматы envelope 1/2 (справочник/документ),
3 (обычный регистр), 4 (RLS-регистр). Старая запись не переинтерпретируется.

Новый комплект: сущность, прежние файлы строк и отдельная конфигурация форм
в пакете сущности. Сначала идут сущность и строки, последней — конфигурация:

```java
@Configuration(proxyBeanMethods = false)
@FormConfigurationFor(OwnedCatalog.class)
@ListColumns({"code", "name"})
@SelectionColumns({"code", "name"})
public final class OwnedCatalogFormConfiguration {
}
```

Литеральный непустой массив аннотации означает EXPLICIT — тот же контракт,
что explicit(...) в нейтральных объявлениях платформы. Совпадение с автоматическим
списком не меняет намерения разработчика. @Configuration обеспечивает обнаружение
конфигурации Spring до первого разрешения метаданных. Зависимости от Vaadin
в entity-design-* не добавлены. Конфликт имени конфигурации с существующим типом
или строкой секции отклоняется семантической проверкой до генерации.

У новых версий снято устаревшее требование OWNER_PROJECTION_UNREPRESENTABLE
о третьей колонке владельца; обязательность, порядок поля и отображение владельца
в проекции сохраняются. В прежних версиях проверка не менялась.

## Режимы и совместимость

FieldApplicationMapping.Projection отдельно сообщает режимы списка и выбора.
Проектировщик задаёт явный состав, поэтому оба режима EXPLICIT.
RuntimeDesignSnapshot.TypeFacts хранит listMode и selectionMode отдельно от
listColumns/selectColumns. Runtime-адаптер получает намерение из окончательных
EntityMetadataInfo.hasExplicit*Columns(), не сравнивает составы по значениям.
Конструкторы прежних сигнатур сохранены; для старого JSON/конструкторов режим
UNKNOWN, а не предположение AUTO или EXPLICIT.

plan-fingerprint-v5 обрамляет прежний полный профиль соответствующего рецепта
и добавляет режимы генерируемого состава и режимы типов runtime-снимка.
Режим при одинаковых разрешённых колонках меняет новый planId; прежние профили
режимы не включают. Захваченные Java fixtures и векторы старых manifest/planId
не перезаписывались. Снимок П.2 не перезаписывался.

## Проверки

| Проверка | Итог | Лог / доказательство |
| --- | --- | --- |
| Выбранные core generation/recipe/manifest/compatibility | 88, 0 ошибок/пропусков | af41-core-verified.log |
| DraftJsonCodecTest, OwnerDraftJsonTest, RegisterDraftJsonTest | 26, 0 ошибок/пропусков | af41-json.log |
| RuntimeAdapterContractTest, CatalogOwnerFactsBridgeTest, RegisterFactsProjectionTest | 12, 0 ошибок/пропусков | af41-runtime.log |
| PatcherGeneratorParityTest (entity-design-source) | 4, 0 ошибок/пропусков | af41-source.log |
| Выбранные тесты приложения | 41, 0 ошибок/пропусков | af41-app-final.log |
| C: справочник, сущность + конфигурация | 5, 0 ошибок/пропусков | outputs/verification/af41/c/catalog-v4/summary.json |
| C: периодический регистр, сущность + конфигурация | 6, 0 ошибок/пропусков | outputs/verification/af41/c/register-v2/summary.json |
| C: неверные профиль, версия, генератор, отсутствие конфигурации | 4 отказа manifest-invalid до Maven | outputs/verification/af41/c-negative/ |
| D: регрессионные и три новые проверки комплектов | 124, 0 ошибок/пропусков | outputs/verification/af41/d/selftests-green/selftest-summary.json |
| Реальный D, успешные C-отчёты и сверка хэшей зависимостей | APPLIED для двух комплектов | outputs/verification/af41/d/catalog-actual/, register-actual/ |
| Отпечатки / bootstrap -ValidateOnly | прошли | af41-fingerprint-*.log, af41-bootstrap.log |

Core проверяет ноль/одну/две секции, владельца, разные составы списка/выбора,
обычный и периодический регистры с RLS и без него, коллизии типов, режимы
при одинаковых колонках и сохранение старого отпечатка. Строки и остальные
декларации сущности побайтно сравниваются с предшественником после удаления
двух переносимых строк массивов.

Приложение: FormSettingsSnapshotTest (4), EntityDesignRuntimeProjectionIT (3),
EntityDraftCatalogAdapterTest (5), EntityDraftJavaDownloadTest (8),
EntityDraftJavaPreviewTest (7), EntityDraftJsonTransferTest (9),
EntityDraftWorkspaceContractTest (3), PatchedModelParityTest (2).
PatchedModelParityTest занял 234.8 секунды; это адресная проверка, а не полный прогон.
Запуск с динамическим attach Mockito был остановлен при зависании; итоговый
успешный запуск использовал явный -javaagent:mockito-core-5.20.0.jar.

C использует backend-адаптер статических аннотаций в проверочном потребителе:
он собирает объявления до первого MetadataResolver. В приложении остаётся
штатный FormColumnDeclarationsCollector. Дополнительный файл конфигурации
не считается строкой секции при выборе обязательного тестового набора C/D.
D проверяет весь CREATE-комплект: применение/NOOP, конфликт второго файла
до записи первого и откат после публикации сущности. Реальные C/D-комплекты
создавались только в outputs/verification/af41/, прикладные файлы не заменялись.

Модули core/json/runtime установлены в обычный ~/.m2/repository. Отпечатки
scripts/local-dependencies.json обновлены только для этих трёх модулей;
проверены diagnose-source-fingerprint и bootstrap -ValidateOnly.

## Границы

AF4.2 (статический читатель конфигураций), AF4.3 (патчер), AF4.4 (команда миграции),
AF4.5 (модели) и AF5.6 не выполнялись. Применение новых CREATE-комплектов через
существующий D не является выполнением команды миграции AF4.4.
Полный прогон приложения, браузерные сценарии и новая визуальная приёмка
не выполнялись. Отдельный сквозной C/D для нового документа, владельца/секций
и RLS-регистра не запускался; эти варианты проверены на уровне генерации и JSON.
Следующая задача — AF4.2.
