#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$draftRoot = Split-Path $PSScriptRoot -Parent
$draftOutput = Join-Path $draftRoot 'outputs/entity-explorer-p8-2'
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $draftRoot
try {
    $env:JAVA_HOME = $JavaHome
    New-Item -ItemType Directory -Force -Path $draftOutput | Out-Null
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" -f entity-design-core/pom.xml clean install *> "$draftOutput/core-install.log"
    if ($LASTEXITCODE -ne 0) { throw 'Fresh core build failed; see core-install.log.' }
    $tests = 'DraftEditorSessionTest,EntityDraftEditorViewTest,EntityDraftWorkspaceTest,EntityDraftWorkspaceContractTest,WorkspaceDirtyGuardTest,WorkspaceRouteStateTest,MainLayoutLeaveGateTest,EntityExplorerViewAddressTest,EntityExplorerViewSpringCreationTest,EntityExplorerAddressWiringTest,EntityExplorerRouteGateTest,EntityExplorerPilotPanelTest,EntityExplorerPilotBoundaryTest,ExplorerFormPreviewTest'
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" '-Dvaadin.skip=true' "-Dtest=$tests" test dependency:build-classpath '-Dmdep.includeScope=runtime' '-Dmdep.outputFile=outputs/entity-explorer-p8-2/runtime-classpath.txt' *> "$draftOutput/maven-tests.log"
    if ($LASTEXITCODE -ne 0) { throw 'Application/editor checks failed; see maven-tests.log.' }
    Write-Output 'P8.2: fresh core install, application compile, selected editor/host/Explorer/preview tests and runtime classpath passed.'
} finally { $env:JAVA_HOME = $previousJavaHome; Pop-Location }
