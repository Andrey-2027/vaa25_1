#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository',
    [string]$TestJvmArguments = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$g1Root = Split-Path $PSScriptRoot -Parent
$g1Output = Join-Path $g1Root 'outputs/entity-generation-g1/g1-1'
$g1VerificationPom = Join-Path $g1Root ('.g1-editor-' + [guid]::NewGuid().ToString('N') + '.pom.xml')
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $g1Root
try {
    $env:JAVA_HOME = $JavaHome
    New-Item -ItemType Directory -Force -Path $g1Output | Out-Null
    $g1ResultsPath = Join-Path $g1Output 'ui-test-results.json'
    if (Test-Path -LiteralPath $g1ResultsPath) { Remove-Item -LiteralPath $g1ResultsPath }
    # Use the installed dependencies. Core install/fingerprints belong to sequential integration with A.
    # Do not clean the shared target or write the shared Maven repository during parallel work.
    $g1Pom = [IO.File]::ReadAllText((Join-Path $g1Root 'pom.xml')).Replace('<build>', '<build><directory>${project.basedir}/outputs/entity-generation-g1/g1-1/build-ui</directory>')
    [IO.File]::WriteAllText($g1VerificationPom, $g1Pom, [Text.UTF8Encoding]::new($false))
    $g1Tests = 'DraftEditorSessionTest,EntityDraftEditorViewTest,EntityDraftJavaPreviewTest,EntityDraftJavaDownloadTest,EntityDraftJsonTransferTest,EntityDraftSectionsTest,EntityDraftUxCompatibilityTest,EntityDraftCatalogAdapterTest,EntityDraftWorkspaceTest,EntityDraftWorkspaceContractTest'
    $g1ExtraArguments = @()
    if ($TestJvmArguments) { $g1ExtraArguments += "-DargLine=$TestJvmArguments" }
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" -f $g1VerificationPom '-Dvaadin.skip=true' "-Dtest=$g1Tests" @g1ExtraArguments test *> "$g1Output/ui-tests.log"
    if ($LASTEXITCODE -ne 0) { throw 'G1 editor checks failed; see outputs/entity-generation-g1/g1-1/ui-tests.log.' }
    $g1Results = foreach ($suite in $g1Tests.Split(',')) {
        $report = @(Get-ChildItem -LiteralPath "$g1Output/build-ui/surefire-reports" -Filter "TEST-*.$suite.xml")
        if ($report.Count -ne 1) { throw "Missing report: $suite" }
        [xml]$xml = Get-Content -LiteralPath $report[0].FullName -Raw
        [ordered]@{ suite=$suite; tests=[int]$xml.testsuite.tests; failures=[int]$xml.testsuite.failures; errors=[int]$xml.testsuite.errors; skipped=[int]$xml.testsuite.skipped }
    }
    [IO.File]::WriteAllText("$g1Output/ui-test-results.json", ($g1Results | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    Write-Output 'G1.1 editor preview/download checks passed. Generated-class compilation is a separate G1.2 gate.'
} finally {
    if (Test-Path -LiteralPath $g1VerificationPom) { Remove-Item -LiteralPath $g1VerificationPom }
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
