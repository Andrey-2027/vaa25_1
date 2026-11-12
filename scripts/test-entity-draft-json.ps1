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
$catalogOutput = Join-Path $catalogRoot 'outputs/entity-explorer-p8-4'
$previousJavaHome = $env:JAVA_HOME
$verificationPom = Join-Path $catalogRoot ('.entity-draft-json-' + [guid]::NewGuid().ToString('N') + '.pom.xml')
Push-Location -LiteralPath $catalogRoot
try {
    $env:JAVA_HOME = $JavaHome
    New-Item -ItemType Directory -Force -Path $catalogOutput | Out-Null
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" -f entity-design-core/pom.xml clean install *> "$catalogOutput/core-install.log"
    if ($LASTEXITCODE -ne 0) { throw 'Fresh core build failed; see core-install.log.' }
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" -f entity-design-json/pom.xml clean install *> "$catalogOutput/json-tests.log"
    if ($LASTEXITCODE -ne 0) { throw 'JSON codec tests failed; see json-tests.log.' }
    $tests = 'EntityDraftJsonTransferTest,EntityDraftCatalogAdapterTest,EntityDraftSectionsTest,DraftEditorSessionTest,EntityDraftEditorViewTest,EntityDraftWorkspaceTest,EntityDraftWorkspaceContractTest,WorkspaceDirtyGuardTest,WorkspaceRouteStateTest,MainLayoutLeaveGateTest,EntityExplorerAddressWiringTest,EntityExplorerRouteGateTest,EntityExplorerPilotPanelTest,EntityExplorerPilotBoundaryTest,ExplorerTreeModelTest,ExplorerFormPreviewTest'
    # Other local tasks may clean target concurrently. Keep this verification's outputs separate.
    $pom = [IO.File]::ReadAllText((Join-Path $catalogRoot 'pom.xml')).Replace('<build>', '<build><directory>${project.basedir}/outputs/entity-explorer-p8-4/build</directory>')
    [IO.File]::WriteAllText($verificationPom, $pom, [Text.UTF8Encoding]::new($false))
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" -f $verificationPom '-Dvaadin.skip=true' "-Dtest=$tests" test dependency:build-classpath '-Dmdep.includeScope=runtime' '-Dmdep.outputFile=outputs/entity-explorer-p8-4/runtime-classpath.txt' *> "$catalogOutput/maven-tests.log"
    if ($LASTEXITCODE -ne 0) { throw 'P8.4 tests failed; see maven-tests.log.' }
    $results = foreach ($suite in $tests.Split(',')) {
        $report = @(Get-ChildItem -LiteralPath "$catalogOutput/build/surefire-reports" -Filter "TEST-*.$suite.xml")
        if ($report.Count -ne 1) { throw "Missing report: $suite" }
        [xml]$xml = Get-Content -LiteralPath $report[0].FullName -Raw
        [ordered]@{ suite=$suite; tests=[int]$xml.testsuite.tests; failures=[int]$xml.testsuite.failures; errors=[int]$xml.testsuite.errors; skipped=[int]$xml.testsuite.skipped }
    }
    [IO.File]::WriteAllText("$catalogOutput/test-results.json", ($results | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Write-Output 'P8.4: fresh core, application/test compilation, JSON/transfer/catalog/sections/editor/host/Explorer/preview tests passed.'
} finally {
    if (Test-Path -LiteralPath $verificationPom) { Remove-Item -LiteralPath $verificationPom }
    $env:JAVA_HOME = $previousJavaHome; Pop-Location
}
