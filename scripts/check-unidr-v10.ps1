# Uses the source-isolated UniDR gate prepared by run-unidr-u4.ps1.
param([string]$JavaHome='C:/Program Files/Axiom/AxiomJDK-21')
& "$PSScriptRoot/check-unidr-v8-4.ps1" -JavaHome $JavaHome -TestClasses @(
    'org.ip.unidr.store.UniDrFileStoreTest',
    'org.ipro.report.catalog.ReportEngineCapabilitiesTest',
    'org.ip.views.reportstudio.ReportCatalogViewTest',
    'org.ip.views.unidr.UniDrFileUiTest',
    'org.ip.unidr.model.UniDrDefinitionJsonCodecTest'
)
exit $LASTEXITCODE
