# Р6: результаты проверки — 2026-10-04

Итог: 478 различных сценариев приняты по совокупности широкого запуска и повторов затронутых проверок. Последние Surefire XML по каждому классу: 478 tests, 0 failures, 0 errors, 0 skipped. Единого широкого BUILD SUCCESS после исправления рендера не заявляется.

## Широкий запуск

Selector:
`CteHibernateExecutorIT,CteHibernateFilterIT,CteHibernateSmokeIT,CteVirtualCatalogIT,JpqlRunServiceTest,NomenclaturePriceCteSpikeTest,NomenclaturePriceFormTest,NomenclaturePriceLifecycleTest,NomenclaturePricePersistenceTest,NomenclaturePriceRegisterOracleTest,NomenclaturePriceRegisterTest,PlatformApiBaselineTest,PlatformArchitectureTest,PlatformAutoConfigurationRegistryTest,PlatformContractsModuleTest,PlatformCoreBoundaryTest,PlatformCoreModuleTest,PlatformCoreSurfaceTest,PlatformDependencyDirectionTest,PlatformEventsModuleTest,PlatformMetadataModuleTest,PlatformNumberingModuleTest,PlatformPackageDependencyMatrixTest,PlatformPersistenceModuleTest,PlatformPublicSurfaceTest,PlatformRlsModuleTest,PlatformSettingsModuleTest,PlatformStarterCompositionTest,PlatformStringDependencyTest,PlatformSubsystemClosureTest,PlatformTelemetryModuleTest,PlatformVaadinModuleTest,PlatformVaadinSurfaceTest,Register*Test,RegisterMetadataValidatorTest,RegisterSliceReportSecurityTest,RegisterStartupValidatorTest,ReportAuthorizationWiringTest,ReportExecutionIT,ReportExecutionServiceOrdersTest,ReportJpqlPreviewControllerIT,ReportQueryAssemblerTest,ReportQueryAssemblyServiceVisualFilterTest,ReportQueryAssemblyServiceVisualParameterIntegrationTest,ReportQueryAssemblyServiceVisualQueryTest,ReportQueryEditorTest,ReportQueryExecutionIT,ReportQueryGuardTest,ReportStudioAutoConfigurationSmokeTest,ReportTemplateTransferServiceTest,SliceSpecTest,TwoDimensionRegisterCteSpikeTest,VisualQueryAggregateExpressionTest,VisualQueryAstJsonTest,VisualQueryCaseTest,VisualQueryCompilerChainTest,VisualQueryCompilerJoinTest,VisualQueryCompilerSecurityTest,VisualQueryCompilerTest,VisualQueryDefinitionJsonCodecTest,VisualQueryDefinitionJsonTest,VisualQueryFilterResolverTest,VisualQueryGuardIT,VisualQueryHavingTest,VisualQueryOrderTest,VisualQueryPackageAssemblyIT,VisualQueryPackageCompilerTest,VisualQueryPackageDeepPathIT,VisualQueryPackageJsonTest,VisualQueryPackageParserIT,VisualQueryPackageParserRoundTripTest,VisualQueryPackageRlsIT,VisualQueryPackageTest,VisualQueryPackageVirtualCatalogTest,VisualQueryParameterRuntimeTest,VisualQueryParameterTest,VisualQueryQ6HibernateIT,VisualQuerySubqueryIT,VisualQuerySubqueryTest,VisualQueryTextParserTest,VisualQueryWhereTest,ReportParamFormTest,ReportCatalogViewTest,ReportRunDialogTest,ReportTemplateServiceTest,ReportTemplateServiceScopeTest,ReportTemplateRoundTripIT,ReportTemplateValidatorTest,ReportTemplateMapperTest,ReportTemplateScopeTransferTest`

```text
[ERROR] Tests run: 5, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 29.60 s <<< FAILURE! -- in org.ip.application.register.NomenclaturePriceCteSpikeTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.006 s -- in org.ip.application.register.NomenclaturePriceFormTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.012 s -- in org.ip.application.register.NomenclaturePriceLifecycleTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.246 s -- in org.ip.application.register.NomenclaturePricePersistenceTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.970 s -- in org.ip.application.register.NomenclaturePriceRegisterOracleTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.467 s -- in org.ip.application.register.TwoDimensionRegisterCteSpikeTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.564 s -- in org.ip.config.JpqlRunServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in org.ip.model.NomenclaturePriceRegisterTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.054 s -- in org.ip.security.ReportStudioAutoConfigurationSmokeTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.382 s -- in org.ip.views.components.ReportParamFormTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.753 s -- in org.ip.views.reportstudio.ReportCatalogViewTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.355 s -- in org.ip.web.ReportJpqlPreviewControllerIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.163 s -- in org.ipro.PlatformApiBaselineTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.327 s -- in org.ipro.PlatformAutoConfigurationRegistryTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.031 s -- in org.ipro.PlatformContractsModuleTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.238 s -- in org.ipro.PlatformCoreBoundaryTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.056 s -- in org.ipro.PlatformCoreModuleTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.804 s -- in org.ipro.PlatformCoreSurfaceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.013 s -- in org.ipro.PlatformEventsModuleTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.015 s -- in org.ipro.PlatformMetadataModuleTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.014 s -- in org.ipro.PlatformNumberingModuleTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.74 s -- in org.ipro.PlatformPackageDependencyMatrixTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in org.ipro.PlatformPersistenceModuleTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.922 s -- in org.ipro.PlatformPublicSurfaceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.010 s -- in org.ipro.PlatformRlsModuleTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.012 s -- in org.ipro.PlatformSettingsModuleTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s -- in org.ipro.PlatformStarterCompositionTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.303 s -- in org.ipro.PlatformStringDependencyTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.368 s -- in org.ipro.PlatformSubsystemClosureTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.021 s -- in org.ipro.PlatformTelemetryModuleTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.164 s -- in org.ipro.PlatformVaadinModuleTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.711 s -- in org.ipro.PlatformVaadinSurfaceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.017 s -- in org.ipro.register.RegisterDescriptorTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.042 s -- in org.ipro.register.RegisterMetadataValidatorMappingTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s -- in org.ipro.register.RegisterMetadataValidatorTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.114 s -- in org.ipro.register.RegisterReaderTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.305 s -- in org.ipro.register.RegisterStartupValidatorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in org.ipro.register.SliceSpecTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.100 s -- in org.ipro.reportstudio.dom.ReportTemplateRoundTripIT
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.058 s -- in org.ipro.reportstudio.dom.ReportTemplateValidatorTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.102 s -- in org.ipro.reportstudio.dto.ReportTemplateMapperTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.328 s -- in org.ipro.reportstudio.query.CteHibernateExecutorIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.559 s -- in org.ipro.reportstudio.query.CteHibernateFilterIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.380 s -- in org.ipro.reportstudio.query.CteHibernateSmokeIT
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.064 s -- in org.ipro.reportstudio.query.CteVirtualCatalogIT
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.065 s -- in org.ipro.reportstudio.query.editor.ReportQueryEditorTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.472 s -- in org.ipro.reportstudio.query.register.RegisterSliceCteGeneratorTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.682 s -- in org.ipro.reportstudio.query.registersecurity.RegisterSliceReportSecurityTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.514 s -- in org.ipro.reportstudio.query.ReportAuthorizationWiringTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in org.ipro.reportstudio.query.ReportQueryAssemblerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.221 s -- in org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualFilterTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.043 s -- in org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualParameterIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.223 s -- in org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualQueryTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.664 s -- in org.ipro.reportstudio.query.ReportQueryExecutionIT
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.281 s -- in org.ipro.reportstudio.query.ReportQueryGuardTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in org.ipro.reportstudio.query.VisualQueryAggregateExpressionTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s -- in org.ipro.reportstudio.query.VisualQueryAstJsonTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.033 s -- in org.ipro.reportstudio.query.VisualQueryCaseTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in org.ipro.reportstudio.query.VisualQueryCompilerChainTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.016 s -- in org.ipro.reportstudio.query.VisualQueryCompilerJoinTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in org.ipro.reportstudio.query.VisualQueryCompilerSecurityTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in org.ipro.reportstudio.query.VisualQueryCompilerTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.023 s -- in org.ipro.reportstudio.query.VisualQueryDefinitionJsonCodecTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.015 s -- in org.ipro.reportstudio.query.VisualQueryDefinitionJsonTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.133 s -- in org.ipro.reportstudio.query.VisualQueryFilterResolverTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.189 s -- in org.ipro.reportstudio.query.VisualQueryGuardIT
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s -- in org.ipro.reportstudio.query.VisualQueryHavingTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in org.ipro.reportstudio.query.VisualQueryOrderTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.072 s -- in org.ipro.reportstudio.query.VisualQueryPackageAssemblyIT
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.009 s -- in org.ipro.reportstudio.query.VisualQueryPackageCompilerTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.119 s -- in org.ipro.reportstudio.query.VisualQueryPackageDeepPathIT
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.028 s -- in org.ipro.reportstudio.query.VisualQueryPackageJsonTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.049 s -- in org.ipro.reportstudio.query.VisualQueryPackageParserIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.002 s -- in org.ipro.reportstudio.query.VisualQueryPackageParserRoundTripTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.050 s -- in org.ipro.reportstudio.query.VisualQueryPackageRlsIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.004 s -- in org.ipro.reportstudio.query.VisualQueryPackageTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0 s -- in org.ipro.reportstudio.query.VisualQueryPackageVirtualCatalogTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in org.ipro.reportstudio.query.VisualQueryParameterRuntimeTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.016 s -- in org.ipro.reportstudio.query.VisualQueryParameterTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.364 s -- in org.ipro.reportstudio.query.VisualQueryQ6HibernateIT
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.764 s -- in org.ipro.reportstudio.query.VisualQuerySubqueryIT
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.035 s -- in org.ipro.reportstudio.query.VisualQuerySubqueryTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.044 s -- in org.ipro.reportstudio.query.VisualQueryTextParserTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in org.ipro.reportstudio.query.VisualQueryWhereTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 5.249 s -- in org.ipro.reportstudio.run.ReportExecutionIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.003 s -- in org.ipro.reportstudio.run.ReportExecutionServiceOrdersTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.269 s -- in org.ipro.reportstudio.service.ReportTemplateServiceScopeTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.031 s -- in org.ipro.reportstudio.service.ReportTemplateServiceTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.007 s -- in org.ipro.reportstudio.transfer.ReportTemplateScopeTransferTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.024 s -- in org.ipro.reportstudio.transfer.ReportTemplateTransferServiceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.822 s -- in org.ipro.PlatformArchitectureTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.610 s -- in org.ipro.PlatformCoreBoundaryTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.058 s -- in org.ipro.PlatformDependencyDirectionTest
[ERROR] Tests run: 454, Failures: 1, Errors: 0, Skipped: 0
[INFO] BUILD FAILURE
[INFO] Total time:  02:19 min
[INFO] Finished at: 2026-10-04T23:22:04+03:00
```

Единственный отказ — PDF-assertion NomenclaturePriceCteSpikeTest: вместо caption шаблона выводились имена CTE-колонок. Исправлен renderer: явные COLUMN captions и формат LocalDate. Повтор ниже подтверждает результат; остальные 453 сценария широкого запуска прошли.

## Проверки фильтров, bindings и архитектуры

Selector: `NomenclaturePriceCteSpikeTest,RegisterSliceCteGeneratorTest,RegisterSliceReportSecurityTest,VisualQueryWhereTest,ReportQueryAssemblyService*Test,VisualQueryHavingTest,QueryFieldFilterFieldResolverTest,ReportParamFormTest,ReportCatalogViewTest,PlatformPublicSurfaceTest,PlatformPackageDependencyMatrixTest,PlatformSubsystemClosureTest`

```text
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 54.11 s -- in org.ip.application.register.NomenclaturePriceCteSpikeTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.550 s -- in org.ip.views.components.ReportParamFormTest
[INFO] Tests run: 22, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.826 s -- in org.ip.views.reportstudio.ReportCatalogViewTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 67.43 s -- in org.ipro.PlatformPackageDependencyMatrixTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 6.432 s -- in org.ipro.PlatformPublicSurfaceTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.114 s -- in org.ipro.PlatformSubsystemClosureTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.031 s -- in org.ipro.reportstudio.query.QueryFieldFilterFieldResolverTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.823 s -- in org.ipro.reportstudio.query.register.RegisterSliceCteGeneratorTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.81 s -- in org.ipro.reportstudio.query.registersecurity.RegisterSliceReportSecurityTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.655 s -- in org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualFilterTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.224 s -- in org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualParameterIntegrationTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.643 s -- in org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualQueryTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.096 s -- in org.ipro.reportstudio.query.VisualQueryHavingTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.303 s -- in org.ipro.reportstudio.query.VisualQueryWhereTest
[INFO] Tests run: 94, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  03:42 min
[INFO] Finished at: 2026-10-04T23:18:09+03:00
```

## Рендер и execution после исправления

Selector: `NomenclaturePriceCteSpikeTest,JasperReportCompilerTest,ReportExecutionIT,ReportExecutionServiceOrdersTest,ReportTemplateRoundTripIT,ReportTemplateTransferServiceTest`

```text
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 58.78 s -- in org.ip.application.register.NomenclaturePriceCteSpikeTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.848 s -- in org.ipro.reportstudio.dom.ReportTemplateRoundTripIT
[INFO] Tests run: 21, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 67.73 s -- in org.ipro.reportstudio.render.JasperReportCompilerTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 16.18 s -- in org.ipro.reportstudio.run.ReportExecutionIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.012 s -- in org.ipro.reportstudio.run.ReportExecutionServiceOrdersTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.048 s -- in org.ipro.reportstudio.transfer.ReportTemplateTransferServiceTest
[INFO] Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:56 min
[INFO] Finished at: 2026-10-04T23:27:52+03:00
```

## Финальная проверка содержимого PDF

Selector: `NomenclaturePriceCteSpikeTest`; проверены caption, дата 02.01.2026, цена, отсутствие null и rs1_ в PDF.

```text
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 70.60 s -- in org.ip.application.register.NomenclaturePriceCteSpikeTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  02:02 min
[INFO] Finished at: 2026-10-04T23:31:22+03:00
```

## Консолидированные результаты по классам

| Класс | Тесты | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| org.ip.application.register.NomenclaturePriceCteSpikeTest | 5 | 0 | 0 | 0 |
| org.ip.application.register.NomenclaturePriceFormTest | 4 | 0 | 0 | 0 |
| org.ip.application.register.NomenclaturePriceLifecycleTest | 4 | 0 | 0 | 0 |
| org.ip.application.register.NomenclaturePricePersistenceTest | 8 | 0 | 0 | 0 |
| org.ip.application.register.NomenclaturePriceRegisterOracleTest | 7 | 0 | 0 | 0 |
| org.ip.application.register.TwoDimensionRegisterCteSpikeTest | 3 | 0 | 0 | 0 |
| org.ip.config.JpqlRunServiceTest | 7 | 0 | 0 | 0 |
| org.ip.model.NomenclaturePriceRegisterTest | 1 | 0 | 0 | 0 |
| org.ip.security.ReportStudioAutoConfigurationSmokeTest | 4 | 0 | 0 | 0 |
| org.ip.views.components.ReportParamFormTest | 6 | 0 | 0 | 0 |
| org.ip.views.reportstudio.ReportCatalogViewTest | 22 | 0 | 0 | 0 |
| org.ip.web.ReportJpqlPreviewControllerIT | 5 | 0 | 0 | 0 |
| org.ipro.PlatformApiBaselineTest | 3 | 0 | 0 | 0 |
| org.ipro.PlatformArchitectureTest | 3 | 0 | 0 | 0 |
| org.ipro.PlatformAutoConfigurationRegistryTest | 3 | 0 | 0 | 0 |
| org.ipro.PlatformContractsModuleTest | 4 | 0 | 0 | 0 |
| org.ipro.PlatformCoreBoundaryTest | 6 | 0 | 0 | 0 |
| org.ipro.PlatformCoreModuleTest | 6 | 0 | 0 | 0 |
| org.ipro.PlatformCoreSurfaceTest | 6 | 0 | 0 | 0 |
| org.ipro.PlatformDependencyDirectionTest | 8 | 0 | 0 | 0 |
| org.ipro.PlatformEventsModuleTest | 2 | 0 | 0 | 0 |
| org.ipro.PlatformMetadataModuleTest | 6 | 0 | 0 | 0 |
| org.ipro.PlatformNumberingModuleTest | 5 | 0 | 0 | 0 |
| org.ipro.PlatformPackageDependencyMatrixTest | 3 | 0 | 0 | 0 |
| org.ipro.PlatformPersistenceModuleTest | 4 | 0 | 0 | 0 |
| org.ipro.PlatformPublicSurfaceTest | 10 | 0 | 0 | 0 |
| org.ipro.PlatformRlsModuleTest | 5 | 0 | 0 | 0 |
| org.ipro.PlatformSettingsModuleTest | 5 | 0 | 0 | 0 |
| org.ipro.PlatformStarterCompositionTest | 5 | 0 | 0 | 0 |
| org.ipro.PlatformStringDependencyTest | 4 | 0 | 0 | 0 |
| org.ipro.PlatformSubsystemClosureTest | 2 | 0 | 0 | 0 |
| org.ipro.PlatformTelemetryModuleTest | 5 | 0 | 0 | 0 |
| org.ipro.PlatformVaadinModuleTest | 7 | 0 | 0 | 0 |
| org.ipro.PlatformVaadinSurfaceTest | 6 | 0 | 0 | 0 |
| org.ipro.register.RegisterDescriptorTest | 4 | 0 | 0 | 0 |
| org.ipro.register.RegisterMetadataValidatorMappingTest | 8 | 0 | 0 | 0 |
| org.ipro.register.RegisterMetadataValidatorTest | 17 | 0 | 0 | 0 |
| org.ipro.register.RegisterReaderTest | 7 | 0 | 0 | 0 |
| org.ipro.register.RegisterStartupValidatorTest | 4 | 0 | 0 | 0 |
| org.ipro.register.SliceSpecTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.dom.ReportTemplateRoundTripIT | 6 | 0 | 0 | 0 |
| org.ipro.reportstudio.dom.ReportTemplateValidatorTest | 38 | 0 | 0 | 0 |
| org.ipro.reportstudio.dto.ReportTemplateMapperTest | 9 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.CteHibernateExecutorIT | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.CteHibernateFilterIT | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.CteHibernateSmokeIT | 4 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.CteVirtualCatalogIT | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.editor.ReportQueryEditorTest | 5 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.QueryFieldFilterFieldResolverTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.register.RegisterSliceCteGeneratorTest | 8 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.registersecurity.RegisterSliceReportSecurityTest | 17 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.ReportAuthorizationWiringTest | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.ReportQueryAssemblerTest | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualFilterTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualParameterIntegrationTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.ReportQueryAssemblyServiceVisualQueryTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.ReportQueryExecutionIT | 7 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.ReportQueryGuardTest | 15 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryAggregateExpressionTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryAstJsonTest | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryCaseTest | 7 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryCompilerChainTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryCompilerJoinTest | 5 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryCompilerSecurityTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryCompilerTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryDefinitionJsonCodecTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryDefinitionJsonTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryFilterResolverTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryGuardIT | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryHavingTest | 7 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryOrderTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryPackageAssemblyIT | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryPackageCompilerTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryPackageDeepPathIT | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryPackageJsonTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryPackageParserIT | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryPackageParserRoundTripTest | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryPackageRlsIT | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryPackageTest | 4 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryPackageVirtualCatalogTest | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryParameterRuntimeTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryParameterTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryQ6HibernateIT | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQuerySubqueryIT | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQuerySubqueryTest | 8 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryTextParserTest | 11 | 0 | 0 | 0 |
| org.ipro.reportstudio.query.VisualQueryWhereTest | 4 | 0 | 0 | 0 |
| org.ipro.reportstudio.render.JasperReportCompilerTest | 21 | 0 | 0 | 0 |
| org.ipro.reportstudio.run.ReportExecutionIT | 4 | 0 | 0 | 0 |
| org.ipro.reportstudio.run.ReportExecutionServiceOrdersTest | 4 | 0 | 0 | 0 |
| org.ipro.reportstudio.service.ReportTemplateServiceScopeTest | 2 | 0 | 0 | 0 |
| org.ipro.reportstudio.service.ReportTemplateServiceTest | 3 | 0 | 0 | 0 |
| org.ipro.reportstudio.transfer.ReportTemplateScopeTransferTest | 1 | 0 | 0 | 0 |
| org.ipro.reportstudio.transfer.ReportTemplateTransferServiceTest | 3 | 0 | 0 | 0 |

Логи, selector, последние XML и изолированный POM: `.local-maven-repository/register-r6/`. Исходный pom.xml сохранён. Команда: Maven 3.9.9, AxiomJDK 21, `-f .register-r6-pom.xml -o -B -ntp -Dmaven.repo.local=C:/Users/strim/.m2/repository -Dtest=<selector> test`; build.directory — `.local-maven-repository/register-r6/target`.

В начале работы полный testCompile блокировался незавершённым assertListBudget в параллельном REST-тесте; узкие промежуточные проверки использовали локальное testExclude. После исправления этой параллельной работы исключение снято. final2/render/pdf-acceptance компилировали всё дерево тестов без исключений. Р6 не меняет REST-тест и не принимает его отдельную функциональность.
