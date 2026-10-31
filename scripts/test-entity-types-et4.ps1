[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [switch]$SkipApplicationCompile,
    [switch]$WriteCoreApiBaseline,
    [switch]$IncludeGlobalChecks
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskOutput = Join-Path $taskRoot 'outputs/et4-final'
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
        '-Dmdep.outputFile=outputs/et4-final/classpath.txt' *> "$taskOutput/classpath.log"
    if ($LASTEXITCODE -ne 0) { throw 'Classpath resolution failed.' }
    $dependencies = (Get-Content "$taskOutput/classpath.txt" -Raw).Trim() -split ';'
    $engine = @($dependencies | Where-Object { (Split-Path $_ -Leaf) -like 'junit-platform-engine-*.jar' })
    if ($engine.Count -ne 1) { throw 'Expected one JUnit platform engine.' }
    $launcher = $engine[0].Replace('junit-platform-engine', 'junit-platform-launcher')
    if (!(Test-Path -LiteralPath $launcher)) { throw "JUnit launcher missing: $launcher" }
    # Maven compile already copies main resources. A second source-resource root makes
    # Boot discover data.sql twice through classpath*:data.sql.
    $classpath = 'target/classes;' + ($dependencies -join ';') + ';' + $launcher
    $architecture = @('PlatformCoreModuleTest','PlatformMetadataModuleTest','PlatformNumberingModuleTest',
        'PlatformVaadinModuleTest','PlatformCoreSurfaceTest','PlatformVaadinSurfaceTest','PlatformSubsystemClosureTest')
    $functional = @('org.ip.application.access.PermissionCatalogServiceTest',
        'org.ip.views.admin.NavigationAdminWiringTest','org.ip.vaadin.explorer.EntitySummaryAssemblerTest',
        'org.ipro.metadata.ManagedEntityCatalogTest','org.ipro.metadata.SectionMetadataRegistryTest',
        'org.ipro.numbering.NumberingMetadataRegistryTest','org.ipro.reportstudio.query.QueryBuilderMetadataCatalogTest',
        'org.ip.views.admin.EntityDraftUxCompatibilityTest')
    $global = @('PlatformPersistenceModuleTest','PlatformStarterCompositionTest','PlatformPublicSurfaceTest')
    $classes = @($architecture + $global + 'PlatformApiBaselineTest' | ForEach-Object { 'org.ipro.' + $_ }) +
        $functional + @('org.ip.metadata.EntityTypesInventoryIT','org.ipro.rls.RlsTestAccessConfiguration')
    $sources = @($classes | ForEach-Object { 'src/test/java/' + $_.Replace('.','/') + '.java' }) +
        'scripts/entity-types-et4/Et4Checks.java'
    $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
        '-sourcepath','src/test/java','-d','outputs/et4-final/app-test-classes') + $sources
    [IO.File]::WriteAllLines("$taskOutput/compile.args", [string[]]$arguments, [Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$taskOutput/compile.args") *> "$taskOutput/checks-compile.log"
    if ($LASTEXITCODE -ne 0) { throw 'Addressed test compilation failed; see checks-compile.log.' }
    $runtime = 'outputs/et4-final/app-test-classes;src/test/resources;' + $classpath
    [IO.File]::WriteAllText("$taskOutput/runtime-classpath.txt", $runtime)
    $agent = @($dependencies | Where-Object { (Split-Path $_ -Leaf) -like 'mockito-core-*.jar' })
    if ($agent.Count -ne 1) { throw 'Expected one Mockito agent.' }
    $java = Join-Path $JavaHome 'bin/java.exe'
    $javaArgs = @('-Dfile.encoding=UTF-8', ('-javaagent:'+$agent[0]), '-cp', $runtime)
    if ($WriteCoreApiBaseline) {
        & $java '-Dplatform.api.baseline.write=true' '-Dplatform.api.baseline.artifact=platform-core' @javaArgs `
            Et4Checks 'org.ipro.PlatformApiBaselineTest#artifactPublicSurfaceMatchesItsReviewedBaseline' *> "$taskOutput/api-write-core.log"
        if ($LASTEXITCODE -ne 0) { throw 'Core API baseline update failed.' }
    }
    & $java @javaArgs Et4Checks @($architecture | ForEach-Object { 'org.ipro.' + $_ }) *> "$taskOutput/architecture.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/architecture.log" -Tail 45; throw 'ET4 architecture checks failed.' }
    foreach ($artifact in @('platform-core','platform-metadata','platform-numbering','platform-spring-boot-autoconfigure','platform-vaadin')) {
        & $java ('-Dplatform.api.baseline.artifact='+$artifact) @javaArgs Et4Checks `
            'org.ipro.PlatformApiBaselineTest#artifactPublicSurfaceMatchesItsReviewedBaseline' *> "$taskOutput/api-$artifact.log"
        if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/api-$artifact.log" -Tail 30; throw "API check failed: $artifact" }
    }
    & $java @javaArgs Et4Checks @functional *> "$taskOutput/application-focused.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/application-focused.log" -Tail 55; throw 'Addressed application checks failed.' }
    & $java '-Dentity.inventory.output=outputs/et4-final/entity-inventory.json' @javaArgs Et4Checks `
        org.ip.metadata.EntityTypesInventoryIT *> "$taskOutput/inventory.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/inventory.log" -Tail 55; throw 'Application inventory check failed.' }
    if ($IncludeGlobalChecks) {
        & $java @javaArgs Et4Checks @($global | ForEach-Object { 'org.ipro.' + $_ }) `
            org.ipro.PlatformApiBaselineTest *> "$taskOutput/global-diagnostics.log"
        if ($LASTEXITCODE -ne 0) { throw 'Global application/refactor checks failed; see global-diagnostics.log.' }
    }
    Write-Output "ET4 addressed architecture, API, application and inventory checks passed. Logs: $taskOutput"
} finally {
    Pop-Location
    $env:JAVA_HOME = $oldJavaHome
}
