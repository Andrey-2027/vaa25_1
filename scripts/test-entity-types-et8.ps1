[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$Python = 'C:/Users/strim/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe',
    [switch]$SkipApplicationCompile,
    [switch]$WriteApiBaseline,
    [switch]$SkipArchitecture,
    [switch]$SkipGlobalChecks
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskOutput = Join-Path $taskRoot 'outputs/et8'
$oldJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $taskRoot
try {
    $env:JAVA_HOME = $JavaHome
    New-Item -ItemType Directory -Force -Path "$taskOutput/app-test-classes" | Out-Null
    if (!$SkipApplicationCompile) {
        & $Maven -o compile *> "$taskOutput/application-compile.log"
        if ($LASTEXITCODE -ne 0) { throw 'Application compilation failed; see application-compile.log.' }
    }
    & $Maven -o dependency:build-classpath '-Dmdep.includeScope=test' `
        '-Dmdep.outputFile=outputs/et8/classpath.txt' *> "$taskOutput/classpath.log"
    if ($LASTEXITCODE -ne 0) { throw 'Classpath resolution failed.' }
    $dependencies = (Get-Content "$taskOutput/classpath.txt" -Raw).Trim() -split ';'
    $engine = @($dependencies | Where-Object { (Split-Path $_ -Leaf) -like 'junit-platform-engine-*.jar' })
    if ($engine.Count -ne 1) { throw 'Expected one JUnit platform engine.' }
    $launcher = $engine[0].Replace('junit-platform-engine', 'junit-platform-launcher')
    if (!(Test-Path -LiteralPath $launcher)) { throw "JUnit launcher missing: $launcher" }
    # Maven compile already copies main resources. A second source-resource root makes
    # Boot discover data.sql twice through classpath*:data.sql.
    $classpath = 'target/classes;' + ($dependencies -join ';') + ';' + $launcher
    $architecture = @('PlatformCoreModuleTest','PlatformVaadinModuleTest','PlatformRegisterModuleTest','PlatformCoreSurfaceTest','PlatformVaadinSurfaceTest','PlatformMetadataModuleTest','PlatformNumberingModuleTest','PlatformContractsModuleTest','PlatformSubsystemClosureTest','PlatformDependencyDirectionTest','PlatformPackageDependencyMatrixTest')
    $functional = @('org.ip.vaadin.explorer.EntitySummaryAssemblerTest',
        'org.ipro.reportstudio.query.QueryBuilderMetadataCatalogTest',
        'org.ip.application.access.PermissionCatalogServiceTest','org.ip.routes.DeepLinkRouteBaselineTest',
        'org.ip.navigation.ApplicationNavigationSecurityTest','org.ipro.crud.InternedEntityContractTest',
        'org.ip.service.AttributeValueServiceTest','org.ip.service.SklNomOpaServiceTest','org.ipro.crud.InternedProfileTest',
        'org.ip.config.ActionPolicyConfigTest','org.ip.vaadin.explorer.EntitySummaryActionPilotTest',
        'org.ip.vaadin.explorer.EntitySummaryReadPlanPilotTest','org.ip.security.RlsProtectedTypeActionDecisionTest')
    $security = @('org.ip.application.catalog.AttributeValueWriteBoundaryIT',
        'org.ip.application.catalog.AttributeValueRenameOperationIT',
        'org.ip.rest.RestReadHttpSecurityIT','org.ip.rest.RestReadHttpContractIT')
    $global = @('PlatformPersistenceModuleTest','PlatformStarterCompositionTest','PlatformPublicSurfaceTest')
    $classes = @($architecture + $global + 'PlatformApiBaselineTest' | ForEach-Object { 'org.ipro.' + $_ }) +
        $functional + $security + @('org.ip.metadata.EntityTypesInventoryIT','org.ipro.rls.RlsTestAccessConfiguration')
    $sources = @($classes | ForEach-Object { 'src/test/java/' + $_.Replace('.','/') + '.java' }) +
        'scripts/entity-types-et8/Et8Checks.java'
    $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
        '-sourcepath','src/test/java','-d','outputs/et8/app-test-classes') + $sources
    [IO.File]::WriteAllLines("$taskOutput/compile.args", [string[]]$arguments, [Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$taskOutput/compile.args") *> "$taskOutput/checks-compile.log"
    if ($LASTEXITCODE -ne 0) { throw 'Addressed test compilation failed; see checks-compile.log.' }
    $runtime = 'outputs/et8/app-test-classes;src/test/resources;' + $classpath
    [IO.File]::WriteAllText("$taskOutput/runtime-classpath.txt", $runtime)
    $agent = @($dependencies | Where-Object { (Split-Path $_ -Leaf) -like 'mockito-core-*.jar' })
    if ($agent.Count -ne 1) { throw 'Expected one Mockito agent.' }
    $java = Join-Path $JavaHome 'bin/java.exe'
    $javaArgs = @('-Dfile.encoding=UTF-8', ('-javaagent:'+$agent[0]), '-cp', $runtime)
    $artifacts = @('platform-contracts','platform-core','platform-metadata','platform-numbering','platform-register','platform-spring-boot-autoconfigure','platform-query','platform-vaadin')
    if ($WriteApiBaseline) {
        foreach ($artifact in $artifacts) {
            & $java '-Dplatform.api.baseline.write=true' ('-Dplatform.api.baseline.artifact='+$artifact) @javaArgs Et8Checks `
                'org.ipro.PlatformApiBaselineTest#artifactPublicSurfaceMatchesItsReviewedBaseline' *> "$taskOutput/api-write-$artifact.log"
            if ($LASTEXITCODE -ne 0) { throw "API baseline update failed: $artifact" }
        }
    }
    if (!$SkipArchitecture) {
        & $java @javaArgs Et8Checks @($architecture | ForEach-Object { 'org.ipro.' + $_ }) *> "$taskOutput/architecture.log"
        if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/architecture.log" -Tail 45; throw 'ET8 architecture checks failed.' }
    }
    foreach ($artifact in $artifacts) {
        & $java ('-Dplatform.api.baseline.artifact='+$artifact) @javaArgs Et8Checks `
            'org.ipro.PlatformApiBaselineTest#artifactPublicSurfaceMatchesItsReviewedBaseline' *> "$taskOutput/api-$artifact.log"
        if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/api-$artifact.log" -Tail 30; throw "API check failed: $artifact" }
    }
    & $java @javaArgs Et8Checks @functional *> "$taskOutput/application-focused.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/application-focused.log" -Tail 55; throw 'Addressed application checks failed.' }
    & $java '-Dentity.inventory.output=outputs/et8/entity-inventory.json' @javaArgs Et8Checks `
        org.ip.metadata.EntityTypesInventoryIT *> "$taskOutput/inventory.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/inventory.log" -Tail 55; throw 'Application inventory check failed.' }
    & $java @javaArgs Et8Checks @security *> "$taskOutput/security-integration.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/security-integration.log" -Tail 55; throw 'Security integration gate failed.' }
    & $Python `
        'scripts/entity-types-et7/compare_publications.py' '--current' 'outputs/et8/entity-inventory.json' `
        '--output' 'outputs/et8/publication-comparison.json' *> "$taskOutput/publication-comparison.log"
    if ($LASTEXITCODE -ne 0) { throw 'ET0 publication comparison failed.' }
    if (!$SkipGlobalChecks) {
        & $java @javaArgs Et8Checks @($global | ForEach-Object { 'org.ipro.' + $_ }) `
            org.ipro.PlatformApiBaselineTest *> "$taskOutput/global-diagnostics.log"
        if ($LASTEXITCODE -ne 0) { throw 'Global application/refactor checks failed; see global-diagnostics.log.' }
    }
    Write-Output "ET8 addressed architecture, API, application and inventory checks passed. Logs: $taskOutput"
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJavaHome
}
