[CmdletBinding()]
param(
    [string]$JavaHome='C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven='C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [switch]$SkipOwners,
    [switch]$SkipCompile,
    [switch]$SkipApplication,
    [switch]$SkipArchitecture,
    [switch]$WriteMeasurements
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskOutput=Join-Path $taskRoot 'outputs/et9'
$oldJava=$env:JAVA_HOME
Push-Location -LiteralPath $taskRoot
try {
    $env:JAVA_HOME=$JavaHome
    New-Item -ItemType Directory -Force "$taskOutput/checks" | Out-Null
    if (!$SkipOwners) {
        & "$PSScriptRoot/test-entity-design-core.ps1" -JavaHome $JavaHome -Maven $Maven -OutputDirectory 'outputs/et9/core-contract'
        foreach ($module in @('entity-design-json','entity-design-runtime')) {
            & $Maven -o -f "$module/pom.xml" test *> "$taskOutput/$module-owner.log"
            if ($LASTEXITCODE -ne 0) { throw "Owner failed: $module" }
        }
    }
    # Install/refresh the three adapter/design artifacts with bootstrap before this gate.
    # A running consumer must never race an install into the same Maven cache.
    foreach ($module in @('entity-design-core','entity-design-json','entity-design-runtime')) {
        $target=Join-Path $taskRoot "$module/target/$module-1.0-SNAPSHOT.jar"
        $installed=Join-Path ([Environment]::GetFolderPath('UserProfile')) ".m2/repository/org/ipro/$module/1.0-SNAPSHOT/$module-1.0-SNAPSHOT.jar"
        if (!(Test-Path $target) -or !(Test-Path $installed) -or (Get-FileHash $target).Hash -ne (Get-FileHash $installed).Hash) {
            throw "Refresh installed $module before consumer acceptance."
        }
    }
    if (!$SkipCompile) {
        & $Maven -o test-compile dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=outputs/et9/classpath.txt' *> "$taskOutput/application-test-compile.log"
        if ($LASTEXITCODE -ne 0) { throw 'Application compilation failed.' }
    }
    $dependencies=(Get-Content "$taskOutput/classpath.txt" -Raw).Trim() -split ';'
    $engine=@($dependencies | Where-Object { (Split-Path $_ -Leaf) -like 'junit-platform-engine-*.jar' })
    if ($engine.Count -ne 1) { throw 'Expected one JUnit engine.' }
    $launcher=$engine[0].Replace('junit-platform-engine','junit-platform-launcher')
    if (!(Test-Path $launcher)) { throw 'JUnit launcher unavailable.' }
    $classpath='target/test-classes;target/classes;src/test/resources;'+($dependencies -join ';')+';'+$launcher
    $arguments=@('--release','21','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),'-d','outputs/et9/checks','scripts/entity-types-et9/Et9Checks.java')
    [IO.File]::WriteAllLines("$taskOutput/launcher-compile.args",[string[]]$arguments,[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$taskOutput/launcher-compile.args") *> "$taskOutput/launcher-compile.log"
    if ($LASTEXITCODE -ne 0) { throw 'Launcher compilation failed.' }
    $agent=@($dependencies | Where-Object { (Split-Path $_ -Leaf) -like 'mockito-core-*.jar' })
    if ($agent.Count -ne 1) { throw 'Expected one Mockito agent.' }
    $runtime='outputs/et9/checks;'+$classpath
    [IO.File]::WriteAllText("$taskOutput/runtime-classpath.txt",$runtime)
    $javaArgs=@('-Dfile.encoding=UTF-8',('-javaagent:'+$agent[0]),'-cp',('"'+$runtime.Replace('\','/')+'"'))
    [IO.File]::WriteAllLines("$taskOutput/java-runtime.args",[string[]]$javaArgs,[Text.UTF8Encoding]::new($false))
    $java=Join-Path $JavaHome 'bin/java.exe'
    & $java ('@'+"$taskOutput/java-runtime.args") Et9Checks '--matrix' "$taskOutput/recipe-matrix.json"
    if ($LASTEXITCODE -ne 0) { throw 'Recipe matrix export failed.' }
    if ($WriteMeasurements) {
        & $java '-Dplatform.measurements.write=true' ('@'+"$taskOutput/java-runtime.args") Et9Checks `
            'org.ipro.PlatformPublicSurfaceTest#documentedMeasurementsMatchTheCode' *> "$taskOutput/measurements.log"
        if ($LASTEXITCODE -ne 0) { throw 'Measurement update failed.' }
    }
    if (!$SkipApplication) {
        $application=@('org.ip.metadata.EntityDesignRuntimeProjectionIT','org.ipro.design.FieldApplicationRuntimeParityTest',
            'org.ip.views.admin.EntityDraftCatalogAdapterTest','org.ip.views.admin.DraftEditorSessionTest',
            'org.ip.views.admin.EntityDraftEditorViewTest','org.ip.views.admin.EntityDraftSectionsTest',
            'org.ip.views.admin.EntityDraftJsonTransferTest','org.ip.views.admin.EntityDraftUxCompatibilityTest',
            'org.ip.views.admin.EntityDraftWorkspaceTest','org.ip.views.workspace.EntityDraftWorkspaceContractTest',
            'org.ip.views.workspace.WorkspaceDirtyGuardTest','org.ip.views.workspace.WorkspaceRouteStateTest',
            'org.ip.views.admin.EntityExplorerViewSpringCreationTest',
            'org.ipro.WorkspaceManifestTest#manifestOrderIsATopologicalOrderOfItsOwnDependencies',
            'org.ipro.WorkspaceManifestTest#everyDeclaredDependencyMatchesTheRealMavenWorkspaceEdges',
            'org.ipro.WorkspaceManifestTest#everyPublishedCoordinateHasASingleOwner')
        & $java '-Dentity.design.snapshot.output=outputs/et9/runtime-snapshot.json' ('@'+"$taskOutput/java-runtime.args") Et9Checks @application *> "$taskOutput/application.log"
        if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/application.log" -Tail 55; throw 'ET9 application/host acceptance failed.' }
    }
    if (!$SkipArchitecture) {
        $architecture=@('PlatformDependencyDirectionTest','PlatformPackageDependencyMatrixTest','PlatformCoreSurfaceTest',
            'PlatformPublicSurfaceTest','PlatformStarterCompositionTest','PlatformContractsModuleTest')
        & $java ('@'+"$taskOutput/java-runtime.args") Et9Checks @($architecture | ForEach-Object { 'org.ipro.'+$_ }) *> "$taskOutput/architecture.log"
        if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/architecture.log" -Tail 50; throw 'ET9 architecture gate failed.' }
    }
    # This stage does not change report-vaadin and deliberately does not run its API baseline.
    foreach ($artifact in @('platform-core','platform-vaadin')) {
        & $java ('-Dplatform.api.baseline.artifact='+$artifact) ('@'+"$taskOutput/java-runtime.args") Et9Checks `
            'org.ipro.PlatformApiBaselineTest#artifactPublicSurfaceMatchesItsReviewedBaseline' *> "$taskOutput/api-$artifact.log"
        if ($LASTEXITCODE -ne 0) { throw "Platform API regression: $artifact" }
    }
    Write-Output 'ET9 selected gates passed. See outputs/et9; skip flags are not evidence for omitted gates.'
} finally { $env:JAVA_HOME=$oldJava; Pop-Location }
