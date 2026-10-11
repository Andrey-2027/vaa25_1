# Г6: подписи RecorderRef и сохранённый отчёт

Дата: 2026-10-06. Статус: выполнена часть приёмки; Г6 остаётся открытым.

## Реализовано

- `RecorderRefPresentation` — безопасная проекция подписи и необязательной ссылки;
  `RecorderRefDisplay` показывает только текст либо ссылку и не является редактируемым полем.
- `RecorderRefPresentationResolver` группирует ссылки по `typeCode`, убирает повторы для
  чтения и режет множество id на пачки по 200. Для обычного чтения использует canonical
  `EntityReadAccess` с fetch paths подписи; для явно заданной entity read policy — её
  `EntityDataAccess`. Ссылку из `FormLinkService` добавляет только после успешного чтения
  документа. Между вызовами resolver результат не кешируется, поэтому изменение прав видно
  при следующем разрешении.
- Неизвестный тип, отсутствующий документ и отказ чтения дают один закрытый текст
  «Документ недоступен» без ссылки. Отсутствие опубликованной формы оставляет разрешённую
  подпись, но не создаёт навигацию.
- `RegisterSliceReportSecurityTest` сохраняет в H2 опубликованный visual report с CTE среза,
  очищает persistence context, повторно загружает шаблон и исполняет его с параметром даты.
  У пользователя есть право читать регистр и его измерение, но нет C5-read на документы;
  результат содержит победившую строку, а SQL не обращается к таблицам документов.
- H2 хранит значение периода с точностью до микросекунд. Интеграционный тест использует
  представимую микросекундную границу `00:00:00.123457`, проверяет прочитанное обратно значение
  и выбор строки отчётом. `ReportParamFormTest` отдельно проверяет, что форма принимает и
  сохраняет в Java значение `LocalDateTime` с наносекундами без потери.
- Для entity-форм `FieldFactory` теперь выбирает `PreciseLocalDateTimeField` для `LocalDateTime`:
  дата выбирается календарём, время вводится как ISO `LocalTime`, включая 1–9 цифр дробной
  секунды. `FormBinding` считает `HasValidation.isInvalid()` ошибкой сохранения даже для
  необязательного поля. Тесты проверяют round-trip `123456789` / `987654321` наносекунд и отказ
  при недопустимом времени; прикладной `NomenclaturePriceFormTest` проверяет это через generic
  entity form и H2 persistence.
- Существующие тесты каталога проверяют сохранение/импорт/экспорт, тесты запуска отчёта —
  Preview и PDF. Эти проверки пока не сведены в один recorder-specific UI сценарий.

## Проверка

Целевой набор из шести классов: `RecorderRefPresentationResolverTest` (6),
`ReportParamFormTest` (7), `ReportCatalogViewTest` (22),
`RegisterSliceReportSecurityTest` (19), `VisualQueryPackageJsonTest` (4),
`ReportExecutionIT` (4). Итого **62 tests, 0 failures, 0 errors, 0 skipped**;
Maven завершился `BUILD SUCCESS` на Maven 3.9.9 / JDK 21 / Hibernate 7.2.12 / H2 2.4.240.

После добавления entity editor весь модуль `platform-vaadin` прошёл: **475 tests, 0 failures,
0 errors, 0 skipped** (`BUILD SUCCESS`). Прикладной `NomenclaturePriceFormTest` прошёл отдельно:
**4 tests, 0 failures, 0 errors, 0 skipped** на H2. Это подтвердило применение нового поля
через реальный `FormResolver` и generic `ItemForm`. Consumer-проверка ролей platform Vaadin
прошла отдельно: `PlatformVaadinSurfaceTest` — **6 tests, 0 failures, 0 errors, 0 skipped**.

Для запуска использован временный `.g6-validation-pom.xml` с ограниченным набором тестов:
корневой POM в этой рабочей копии не разрешает версию новой зависимости `platform-rest-web`
из установленного локального BOM. Полный root build этим результатом не подтверждён.

## Что осталось для закрытия Г6

В production-модели пока нет регистра, который использует `RecordedRegisterEntry`; `NomenclaturePrice`
остаётся самостоятельным регистром без регистратора. Поэтому read-only компонент ещё не встроен
в фактическую таблицу строк, CRUD-кнопки generic списка не проверены на production-форме, а EXACT
редактор не проверен на выбранном предметном регистре. В Г7 нужно выбрать регистр-пилот и подключить
presentation/list behavior к нему. После этого выполнить единый сохранённый recorder-report
сценарий от формы запуска через Preview до PDF и остальных поддержанных форматов.
