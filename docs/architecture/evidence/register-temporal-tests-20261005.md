# Период LocalDateTime и календарные режимы: проверка 2026-10-05

Результат: **170 различных тестов, 0 failures, 0 errors, 0 skipped** по последним Surefire XML
для объединения двух selector. Широкий запуск — 147 тестов, завершающий запуск — 55;
пересекающиеся классы повторно не суммируются. Оба Maven-запуска завершились BUILD SUCCESS.

## Проверенные сценарии

- LocalDateTime в metadata validation, inherited fields, descriptor, point reader и SliceSpec.
- Реальные Criteria getLast и CTE/H2 различают соседние микросекунды внутри одной миллисекунды;
  граница включительная, до первой записи результат пустой.
- Literal bindings и runtime DATETIME сохраняют наносекунды. Неверные типы/отсутствующие
  обязательные параметры отклоняются до запроса. DATE для обычных запросов сохранён.
- Календарные DAY/MONTH/YEAR: начала интервалов, последний момент интервала, идемпотентность;
  listener и period из mapped superclass, диагностика отсутствующего normalizer/period.
- DAY-пилот: canonical и прямой JPA INSERT нормализуют время; resource update внутри того же
  дневного интервала сохраняет ключ. Прежние запреты изменения даты/измерения и unique сохраняются.
- Сохранение/экспорт/импорт отчёта, oracle parity, RLS/C5 и запрет возврата старой строки
  вместо скрытой/отфильтрованной последней версии.
- Форма DATETIME после замены теряющего точность DateTimePicker сохраняет 9 знаков и
  диагностирует дату без времени. Jasper/PDF сохраняет `14:20:30.123456789`.
- Public surface, documented measurements, package matrix, subsystem closure и architecture gates.

## Среда и команды

AxiomJDK 21, bundled Maven 3.9.9, Hibernate 7.2.12.Final, H2 2.4.240. Maven offline.
`pom.xml` не изменялся. Существующая внешняя БД не использовалась; PostgreSQL SQL не выполнялся.
Реальная PostgreSQL/другая СУБД и её точность TIMESTAMP требуют отдельной проверки при переходе.

Префикс команд: Maven с `-o -B -Dmaven.repo.local=C:/Users/strim/.m2/repository
-Dmaven.compiler.fork=true -Dvaadin.skip=true`; JAVA_HOME указывает на AxiomJDK-21.

Первый selector:
`NomenclaturePrice*Test,TwoDimensionRegisterCteSpikeTest,Register*Test,SliceSpecTest,ReportParamFormTest,VisualQueryParameterRuntimeTest,ReportStudioAutoConfigurationSmokeTest,PlatformPublicSurfaceTest,PlatformPackageDependencyMatrixTest,PlatformCoreSurfaceTest,PlatformArchitectureTest,PlatformSubsystemClosureTest`.
Для пересчёта двух новых платформенных типов добавлялся `-Dplatform.measurements.write=true`.

Завершающий selector:
`RegisterSliceCteGeneratorTest,ReportParamFormTest,JasperReportCompilerTest,PlatformPublicSurfaceTest,PlatformSubsystemClosureTest,PlatformPackageDependencyMatrixTest`.
Поверхность/замыкание проверены уже без режима записи. Повторы вызваны исправлениями формы/рендера
и добавлением проверок точечного чтения и PDF, а не расширением до полного тестового набора.

Логи: `target/register-temporal-tests-final.log`, `target/register-temporal-precision.log`.
В forked javac логируется исключение JDK ZipFileSystem при закрытии read-only JAR в sandbox;
Maven завершает сборку успешно, новые тесты присутствуют в XML и выполняются. In-process compiler
ранее останавливался на том же AccessDenied. Это диагностическое ограничение окружения.

SHA-256 исходников: [register-temporal-sources-20261005.sha256](register-temporal-sources-20261005.sha256).

## Последние результаты классов

| Класс | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| org.ip.application.register.NomenclaturePriceCteSpikeTest | 5 | 0 | 0 | 0 |
| org.ip.application.register.NomenclaturePriceFormTest | 4 | 0 | 0 | 0 |
| org.ip.application.register.NomenclaturePriceLifecycleTest | 4 | 0 | 0 | 0 |
| org.ip.application.register.NomenclaturePricePersistenceTest | 11 | 0 | 0 | 0 |
| org.ip.application.register.NomenclaturePriceRegisterOracleTest | 7 | 0 | 0 | 0 |
| org.ip.application.register.TwoDimensionRegisterCteSpikeTest | 3 | 0 | 0 | 0 |
| org.ip.model.NomenclaturePriceRegisterTest | 1 | 0 | 0 | 0 |
| org.ip.security.ReportStudioAutoConfigurationSmokeTest | 4 | 0 | 0 | 0 |
| org.ip.views.components.ReportParamFormTest | 7 | 0 | 0 | 0 |
| org.ipro.PlatformArchitectureTest | 3 | 0 | 0 | 0 |
| org.ipro.PlatformCoreSurfaceTest | 6 | 0 | 0 | 0 |
| org.ipro.PlatformPackageDependencyMatrixTest | 3 | 0 | 0 | 0 |
| org.ipro.PlatformPublicSurfaceTest | 10 | 0 | 0 | 0 |
| org.ipro.PlatformSubsystemClosureTest | 2 | 0 | 0 | 0 |
| org.ipro.register.RegisterDescriptorTest | 4 | 0 | 0 | 0 |
| org.ipro.register.RegisterMetadataValidatorMappingTest | 8 | 0 | 0 | 0 |
| org.ipro.register.RegisterMetadataValidatorTest | 17 | 0 | 0 | 0 |
| org.ipro.register.RegisterPeriodicityTest | 4 | 0 | 0 | 0 |
| org.ipro.register.RegisterReaderTest | 7 | 0 | 0 | 0 |
| org.ipro.register.RegisterStartupValidatorTest | 4 | 0 | 0 | 0 |
| org.ipro.register.SliceSpecTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryParameterRuntimeTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.register.RegisterSliceCteGeneratorTest | 11 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.registersecurity.RegisterSliceReportSecurityTest | 17 | 0 | 0 | 0 |
| org.ipro.reportstudio.render.JasperReportCompilerTest | 22 | 0 | 0 | 0 |
