#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository',
    [switch]$SkipP8
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$matrixRoot = Split-Path $PSScriptRoot -Parent
$matrixBase = Join-Path $matrixRoot 'outputs/entity-generation-g1/matrix-20261029'
$matrixRun = Join-Path $matrixBase ('run-' + (Get-Date -Format yyyyMMdd-HHmmss))
$matrixSupport = Join-Path $PSScriptRoot 'entity-generation-g1-matrix'
$matrixPreviousJava = $env:JAVA_HOME
$matrixPom = Join-Path $matrixRoot ('.matrix-' + [guid]::NewGuid().ToString('N') + '.pom.xml')
Push-Location -LiteralPath $matrixRoot
try {
    $env:JAVA_HOME = $JavaHome
    New-Item -ItemType Directory -Force -Path $matrixRun | Out-Null
    if (-not $SkipP8) {
        $matrixXml = [IO.File]::ReadAllText((Join-Path $matrixRoot 'pom.xml')).Replace('<build>', '<build><directory>${project.basedir}/outputs/entity-generation-g1/matrix-20261029/build-ui</directory>')
        [IO.File]::WriteAllText($matrixPom, $matrixXml, [Text.UTF8Encoding]::new($false))
        $matrixAgent = Join-Path $LocalRepository 'org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar'
        $matrixErrorAction = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            & $Maven -o -f $matrixPom "-Dmaven.repo.local=$LocalRepository" '-Dvaadin.skip=true' '-Dtest=EntityDraftMatrixScenarioTest' "-DargLine=-javaagent:$matrixAgent" test *> "$matrixBase/p8-preview-download.log"
            $matrixP8Exit = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $matrixErrorAction
        }
        if ($matrixP8Exit -ne 0) { throw 'P8 Matrix preview/download failed' }
    }
    Copy-Item -LiteralPath "$matrixBase/input" -Destination "$matrixRun/input" -Recurse
    Copy-Item -LiteralPath "$matrixBase/p8-preview-download.log" -Destination "$matrixRun/p8-preview-download.log"
    Copy-Item -LiteralPath "$matrixBase/build-ui/surefire-reports/TEST-org.ip.views.admin.EntityDraftMatrixScenarioTest.xml" -Destination "$matrixRun/p8-test.xml"
    Copy-Item -LiteralPath "$matrixSupport/ScenarioSpec.java" -Destination "$matrixRun/ScenarioSpec.java"
    $matrixReceipt = Get-Content -Raw "$matrixRun/input/plan-receipt.json" | ConvertFrom-Json
    $matrixSource = Join-Path "$matrixRun/input" $matrixReceipt.relativePath
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRoot/test-entity-generation-g1.ps1" -SourceFile $matrixSource -RelativePath $matrixReceipt.relativePath -ExpectedSha256 $matrixReceipt.sha256 -SpecFile "$matrixSupport/ScenarioSpec.java" -PlanReceipt "$matrixRun/input/plan-receipt.json" -OutputDirectory "$matrixRun/c" -RunId matrix -JavaHome $JavaHome -Maven $Maven -LocalRepository $LocalRepository -KeepIsolation *> "$matrixRun/c-launcher.log"
    if ($LASTEXITCODE -ne 0) { throw 'Matrix generated-acceptance C failed' }
    $matrixSummary = Join-Path $matrixRun 'c/matrix/summary.json'
    $matrixClasses = Join-Path $matrixRun 'support-classes'
    New-Item -ItemType Directory -Force -Path $matrixClasses | Out-Null
    $matrixJars = @(
        "$matrixRoot/entity-design-core/target/classes",
        "$matrixRoot/entity-design-json/target/classes",
        "$LocalRepository/com/fasterxml/jackson/core/jackson-databind/2.18.4/jackson-databind-2.18.4.jar",
        "$LocalRepository/com/fasterxml/jackson/core/jackson-core/2.18.4/jackson-core-2.18.4.jar",
        "$LocalRepository/com/fasterxml/jackson/core/jackson-annotations/2.18.4/jackson-annotations-2.18.4.jar"
    )
    $matrixCp = $matrixJars -join ';'
    $matrixSources = @((Get-ChildItem -LiteralPath "$PSScriptRoot/entity-generation-g1-apply/src/main/java" -Recurse -Filter '*.java').FullName) + @("$matrixSupport/MatrixApply.java")
    & "$JavaHome/bin/javac.exe" --release 21 -encoding UTF-8 -cp $matrixCp -d $matrixClasses @matrixSources *> "$matrixRun/javac-apply.log"
    if ($LASTEXITCODE -ne 0) { throw 'Matrix apply caller compilation failed' }
    $matrixProject = Join-Path $matrixRun 'project'
    New-Item -ItemType Directory -Force -Path $matrixProject | Out-Null
    Copy-Item -Path "$PSScriptRoot/entity-generation-g1/consumer/*" -Destination $matrixProject -Recurse
    Copy-Item -LiteralPath "$matrixSupport/ScenarioSpec.java" -Destination "$matrixProject/src/test/java/com/example/generated/harness/ScenarioSpec.java"
    Copy-Item -LiteralPath "$matrixSupport/MatrixRuntimeTest.java" -Destination "$matrixProject/src/test/java/com/example/generated/harness/MatrixRuntimeTest.java"
    $matrixApplyCp = "$matrixClasses;$matrixCp"
    & "$JavaHome/bin/java.exe" -cp $matrixApplyCp org.ipro.design.scenario.MatrixApply "$matrixRun/input" $matrixProject $matrixSummary "$matrixRun/applied.json" APPLIED *> "$matrixRun/apply.log"
    if ($LASTEXITCODE -ne 0) { throw 'Matrix CREATE apply failed' }
    & "$JavaHome/bin/java.exe" -cp $matrixApplyCp org.ipro.design.scenario.MatrixApply "$matrixRun/input" $matrixProject $matrixSummary "$matrixRun/noop.json" NOOP *> "$matrixRun/noop.log"
    if ($LASTEXITCODE -ne 0) { throw 'Matrix repeat/noop failed' }
    # Build the exact project D populated, not a manually supplied replacement entity.
    $matrixErrorAction = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Maven -o -f "$matrixProject/pom.xml" "-Dmaven.repo.local=$LocalRepository" test *> "$matrixRun/applied-project-tests.log"
        $matrixBuildExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $matrixErrorAction
    }
    if ($matrixBuildExit -ne 0) { throw 'Matrix applied-project build/runtime checks failed' }
    $matrixConflict = Join-Path $matrixRun 'conflict-project'
    Copy-Item -LiteralPath $matrixProject -Destination $matrixConflict -Recurse
    & "$JavaHome/bin/java.exe" -cp $matrixApplyCp org.ipro.design.scenario.MatrixApply "$matrixRun/input" $matrixConflict $matrixSummary "$matrixRun/conflict.json" CONFLICT *> "$matrixRun/conflict.log"
    if ($LASTEXITCODE -ne 0) { throw 'Matrix changed-source conflict/preservation failed' }
    & "$matrixSupport/Write-Summary.ps1" -RunDirectory $matrixRun
} finally {
    if (Test-Path -LiteralPath $matrixPom) { Remove-Item -LiteralPath $matrixPom }
    $env:JAVA_HOME = $matrixPreviousJava
    Pop-Location
}
