# Р5: результаты проверки — 2026-10-04

Проверены 360 различных тестовых сценариев. Широкий запуск: 359 успешных сценариев и один отказ архитектурного gate. После точечного исправления test-usage весь класс gate (6 тестов) повторён успешно. Полный широкий запуск повторно не выполнялся.

## Широкий запуск

Selector:
`CteHibernateExecutorIT,CteHibernateFilterIT,CteHibernateSmokeIT,CteVirtualCatalogIT,JpqlRunServiceTest,NomenclaturePriceCteSpikeTest,NomenclaturePriceFormTest,NomenclaturePriceLifecycleTest,NomenclaturePricePersistenceTest,NomenclaturePriceRegisterOracleTest,NomenclaturePriceRegisterTest,PlatformApiBaselineTest,PlatformArchitectureTest,PlatformAutoConfigurationRegistryTest,PlatformContractsModuleTest,PlatformCoreBoundaryTest,PlatformCoreModuleTest,PlatformCoreSurfaceTest,PlatformDependencyDirectionTest,PlatformEventsModuleTest,PlatformMetadataModuleTest,PlatformNumberingModuleTest,PlatformPackageDependencyMatrixTest,PlatformPersistenceModuleTest,PlatformPublicSurfaceTest,PlatformRlsModuleTest,PlatformSettingsModuleTest,PlatformStarterCompositionTest,PlatformStringDependencyTest,PlatformSubsystemClosureTest,PlatformTelemetryModuleTest,PlatformVaadinModuleTest,PlatformVaadinSurfaceTest,Register*Test,RegisterMetadataValidatorTest,RegisterSliceReportSecurityTest,RegisterStartupValidatorTest,ReportAuthorizationWiringTest,ReportExecutionIT,ReportExecutionServiceOrdersTest,ReportJpqlPreviewControllerIT,ReportQueryAssemblerTest,ReportQueryAssemblyServiceVisualFilterTest,ReportQueryAssemblyServiceVisualParameterIntegrationTest,ReportQueryAssemblyServiceVisualQueryTest,ReportQueryEditorTest,ReportQueryExecutionIT,ReportQueryGuardTest,ReportStudioAutoConfigurationSmokeTest,ReportTemplateTransferServiceTest,SliceSpecTest,TwoDimensionRegisterCteSpikeTest,VisualQueryAggregateExpressionTest,VisualQueryAstJsonTest,VisualQueryCaseTest,VisualQueryCompilerChainTest,VisualQueryCompilerJoinTest,VisualQueryCompilerSecurityTest,VisualQueryCompilerTest,VisualQueryDefinitionJsonCodecTest,VisualQueryDefinitionJsonTest,VisualQueryFilterResolverTest,VisualQueryGuardIT,VisualQueryHavingTest,VisualQueryOrderTest,VisualQueryPackageAssemblyIT,VisualQueryPackageCompilerTest,VisualQueryPackageDeepPathIT,VisualQueryPackageJsonTest,VisualQueryPackageParserIT,VisualQueryPackageParserRoundTripTest,VisualQueryPackageRlsIT,VisualQueryPackageTest,VisualQueryPackageVirtualCatalogTest,VisualQueryParameterRuntimeTest,VisualQueryParameterTest,VisualQueryQ6HibernateIT,VisualQuerySubqueryIT,VisualQuerySubqueryTest,VisualQueryTextParserTest,VisualQueryWhereTest`

```text
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 50.82 s -- in org.ip.application.register.NomenclaturePriceCteSpikeTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 11.42 s -- in org.ip.application.register.NomenclaturePriceFormTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.028 s -- in org.ip.application.register.NomenclaturePriceLifecycleTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.535 s -- in org.ip.application.register.NomenclaturePricePersistenceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.401 s -- in org.ip.application.register.NomenclaturePriceRegisterOracleTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.304 s -- in org.ip.application.register.TwoDimensionRegisterCteSpikeTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.809 s -- in org.ip.config.JpqlRunServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in org.ip.model.NomenclaturePriceRegisterTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.156 s -- in org.ip.security.ReportStudioAutoConfigurationSmokeTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 15.07 s -- in org.ip.web.ReportJpqlPreviewControllerIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.401 s -- in org.ipro.PlatformApiBaselineTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.851 s -- in org.ipro.PlatformAutoConfigurationRegistryTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.070 s -- in org.ipro.PlatformContractsModuleTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 14.29 s -- in org.ipro.PlatformCoreBoundaryTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.120 s -- in org.ipro.PlatformCoreModuleTest
[ERROR] Tests run: 6, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 2.307 s <<< FAILURE! -- in org.ipro.PlatformCoreSurfaceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in org.ipro.PlatformEventsModuleTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.033 s -- in org.ipro.PlatformMetadataModuleTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.028 s -- in org.ipro.PlatformNumberingModuleTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 52.74 s -- in org.ipro.PlatformPackageDependencyMatrixTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.019 s -- in org.ipro.PlatformPersistenceModuleTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.669 s -- in org.ipro.PlatformPublicSurfaceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.030 s -- in org.ipro.PlatformRlsModuleTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.024 s -- in org.ipro.PlatformSettingsModuleTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.044 s -- in org.ipro.PlatformStarterCompositionTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.802 s -- in org.ipro.PlatformStringDependencyTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.854 s -- in org.ipro.PlatformSubsystemClosureTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.040 s -- in org.ipro.PlatformTelemetryModuleTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.407 s -- in org.ipro.PlatformVaadinModuleTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 23.17 s -- in org.ipro.PlatformVaadinSurfaceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.020 s -- in org.ipro.register.RegisterDescriptorTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.122 s -- in org.ipro.register.RegisterMetadataValidatorMappingTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.058 s -- in org.ipro.register.RegisterMetadataValidatorTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.453 s -- in org.ipro.register.RegisterReaderTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.000 s -- in org.ipro.register.RegisterStartupValidatorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in org.ipro.register.SliceSpecTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.167 s -- in org.ipro.reportstudio.query.CteHibernateExecutorIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.796 s -- in org.ipro.reportstudio.query.CteHibernateFilterIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.886 s -- in org.ipro.reportstudio.query.CteHibernateSmokeIT
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.163 s -- in org.ipro.reportstudio.query.CteVirtualCatalogIT
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.719 s -- in org.ipro.reportstudio.query.editor.ReportQueryEditorTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.361 s -- in org.ipro.reportstudio.query.register.RegisterSliceCteGeneratorTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 10.51 s -- in org.ipro.reportstudio.query.registersecurity.RegisterSliceReportSecurityTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.217 s -- in org.ipro.reportstudio.query.ReportAuthorizationWiringTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in org.ipro.reportstudio.query.ReportQueryAssemblerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.828 s -- in org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualFilterTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.156 s -- in org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualParameterIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.538 s -- in org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualQueryTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.529 s -- in org.ipro.reportstudio.query.ReportQueryExecutionIT
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.803 s -- in org.ipro.reportstudio.query.ReportQueryGuardTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.012 s -- in org.ipro.reportstudio.query.VisualQueryAggregateExpressionTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.042 s -- in org.ipro.reportstudio.query.VisualQueryAstJsonTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.072 s -- in org.ipro.reportstudio.query.VisualQueryCaseTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in org.ipro.reportstudio.query.VisualQueryCompilerChainTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.041 s -- in org.ipro.reportstudio.query.VisualQueryCompilerJoinTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in org.ipro.reportstudio.query.VisualQueryCompilerSecurityTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.017 s -- in org.ipro.reportstudio.query.VisualQueryCompilerTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.047 s -- in org.ipro.reportstudio.query.VisualQueryDefinitionJsonCodecTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.043 s -- in org.ipro.reportstudio.query.VisualQueryDefinitionJsonTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.302 s -- in org.ipro.reportstudio.query.VisualQueryFilterResolverTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.611 s -- in org.ipro.reportstudio.query.VisualQueryGuardIT
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.061 s -- in org.ipro.reportstudio.query.VisualQueryHavingTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in org.ipro.reportstudio.query.VisualQueryOrderTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.190 s -- in org.ipro.reportstudio.query.VisualQueryPackageAssemblyIT
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.009 s -- in org.ipro.reportstudio.query.VisualQueryPackageCompilerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.329 s -- in org.ipro.reportstudio.query.VisualQueryPackageDeepPathIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.089 s -- in org.ipro.reportstudio.query.VisualQueryPackageJsonTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.129 s -- in org.ipro.reportstudio.query.VisualQueryPackageParserIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in org.ipro.reportstudio.query.VisualQueryPackageParserRoundTripTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.162 s -- in org.ipro.reportstudio.query.VisualQueryPackageRlsIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.011 s -- in org.ipro.reportstudio.query.VisualQueryPackageTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in org.ipro.reportstudio.query.VisualQueryPackageVirtualCatalogTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in org.ipro.reportstudio.query.VisualQueryParameterRuntimeTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.040 s -- in org.ipro.reportstudio.query.VisualQueryParameterTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.197 s -- in org.ipro.reportstudio.query.VisualQueryQ6HibernateIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.413 s -- in org.ipro.reportstudio.query.VisualQuerySubqueryIT
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.117 s -- in org.ipro.reportstudio.query.VisualQuerySubqueryTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.123 s -- in org.ipro.reportstudio.query.VisualQueryTextParserTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.008 s -- in org.ipro.reportstudio.query.VisualQueryWhereTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 25.10 s -- in org.ipro.reportstudio.run.ReportExecutionIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.014 s -- in org.ipro.reportstudio.run.ReportExecutionServiceOrdersTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.473 s -- in org.ipro.reportstudio.transfer.ReportTemplateTransferServiceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.467 s -- in org.ipro.PlatformArchitectureTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.137 s -- in org.ipro.PlatformCoreBoundaryTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.154 s -- in org.ipro.PlatformDependencyDirectionTest
[ERROR] Tests run: 360, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[INFO] Total time:  05:20 min
[INFO] Finished at: 2026-10-04T22:16:19+03:00
```

Единственный отказ — PlatformCoreSurfaceTest.applicationTestReferencesToInternalOrModuleTypesAreFrozenPerFile: test-usage CanonicalWriteExecutor в параллельно изменённом RestReadPipelinePilotIT. Уточнён только этот test-usage; роль MODULE_API и production surface сохранены. В тест также добавлен отсутствовавший import RestDetailResult, необходимый для компиляции текущих исходников.

## Повтор архитектурного gate

Selector: `PlatformCoreSurfaceTest`

```text
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.467 s -- in org.ipro.PlatformCoreSurfaceTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  01:01 min
[INFO] Finished at: 2026-10-04T22:19:31+03:00
```

Логи и изолированный POM: `.local-maven-repository/register-r5/`. Исходный pom.xml не изменялся. Команда: bundled Maven 3.9.9, AxiomJDK 21, `-f .register-r5-pom.xml -o -B -ntp -Dmaven.repo.local=C:/Users/strim/.m2/repository -Dtest=<selector> test`; только build.directory направлен в `.local-maven-repository/register-r5/target`.
