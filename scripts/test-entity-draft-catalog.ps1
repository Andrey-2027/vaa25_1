#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$catalogRoot = Split-Path $PSScriptRoot -Parent
$catalogOutput = Join-Path $catalogRoot 'outputs/entity-explorer-p8-3'
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $catalogRoot
try {
    $env:JAVA_HOME = $JavaHome
    New-Item -ItemType Directory -Force -Path $catalogOutput | Out-Null
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" -f entity-design-core/pom.xml clean install *> "$catalogOutput/core-install.log"
    if ($LASTEXITCODE -ne 0) { throw 'Fresh core build failed; see core-install.log.' }
    $tests = 'EntityDraftCatalogAdapterTest,EntityDraftSectionsTest,DraftEditorSessionTest,EntityDraftEditorViewTest,EntityDraftWorkspaceTest,EntityDraftWorkspaceContractTest,WorkspaceDirtyGuardTest,WorkspaceRouteStateTest,MainLayoutLeaveGateTest,EntityExplorerAddressWiringTest,EntityExplorerRouteGateTest,EntityExplorerPilotPanelTest,EntityExplorerPilotBoundaryTest,ExplorerTreeModelTest,ExplorerFormPreviewTest'
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" '-Dvaadin.skip=true' "-Dtest=$tests" test dependency:build-classpath '-Dmdep.includeScope=runtime' '-Dmdep.outputFile=outputs/entity-explorer-p8-3/runtime-classpath.txt' *> "$catalogOutput/maven-tests.log"
    if ($LASTEXITCODE -ne 0) { throw 'P8.3 tests failed; see maven-tests.log.' }
    $results = foreach ($suite in $tests.Split(',')) {
        $report = @(Get-ChildItem -LiteralPath 'target/surefire-reports' -Filter "TEST-*.$suite.xml")
        if ($report.Count -ne 1) { throw "Missing report: $suite" }
        [xml]$xml = Get-Content -LiteralPath $report[0].FullName -Raw
        [ordered]@{ suite=$suite; tests=[int]$xml.testsuite.tests; failures=[int]$xml.testsuite.failures; errors=[int]$xml.testsuite.errors; skipped=[int]$xml.testsuite.skipped }
    }
    [IO.File]::WriteAllText("$catalogOutput/test-results.json", ($results | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Write-Output 'P8.3: fresh core, application/test compilation, catalog/sections/editor/host/Explorer/preview tests passed.'
} finally { $env:JAVA_HOME = $previousJavaHome; Pop-Location }
