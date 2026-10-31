[CmdletBinding()]
param(
    [string]$MavenCommand,
    [string]$JavaHome = 'C:/Program Files/Axiom/AxiomJDK-21',
    [switch]$Full,
    [switch]$SkipCompile,
    [switch]$ForceCompile,
    [switch]$ForceInstall,
    [string]$TestPattern
)

# Tiered UniDR check. The full targeted application run takes ~8 minutes because
# UniDrReportCompilerTest alone renders PDF/XLSX for ~3.5 minutes. Day-to-day
# moves, imports and port wiring only need the module tests, app test-compile and
# the curated unit set below; run -Full (or pass -TestPattern) before closing a
# slice or when touching rendering, Postgres or UI sources.
#
# Measured on 2026-10-25 (8 cores, Windows):
#   default, nothing changed  ~31s  (module 9s + surefire 22s)
#   default, after an edit    ~80-120s (adds the application test-compile)
#   -Full                     ~8min (same 325 tests as the old command)
#
# Examples:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-unidr-fast.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-unidr-fast.ps1 -Full
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-unidr-fast.ps1 -TestPattern UniDrQueryServiceTest
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-unidr-fast.ps1 -ForceCompile
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $PSCommandPath
$projectDirectory = Split-Path -Parent $scriptDirectory

function Resolve-MavenCommand {
    param([string]$RequestedCommand)

    if ($RequestedCommand) {
        $resolved = [System.IO.Path]::GetFullPath($RequestedCommand)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw "Maven executable not found: $resolved"
        }
        return $resolved
    }

    $fromPath = Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue
    if ($fromPath) { return $fromPath.Source }

    if ($env:IDEA_MAVEN_HOME) {
        $fromEnvironment = Join-Path $env:IDEA_MAVEN_HOME 'bin\mvn.cmd'
        if (Test-Path -LiteralPath $fromEnvironment -PathType Leaf) {
            return [System.IO.Path]::GetFullPath($fromEnvironment)
        }
    }

    $jetBrainsDirectory = Join-Path $env:ProgramFiles 'JetBrains'
    if (Test-Path -LiteralPath $jetBrainsDirectory -PathType Container) {
        $candidates = Get-ChildItem -LiteralPath $jetBrainsDirectory -Directory -Filter 'IntelliJ IDEA*' |
            ForEach-Object {
                Get-Item -LiteralPath (Join-Path $_.FullName 'plugins\maven\lib\maven3\bin\mvn.cmd') `
                    -ErrorAction SilentlyContinue
            } |
            Sort-Object LastWriteTime -Descending
        if ($candidates) { return $candidates[0].FullName }
    }

    throw 'Maven was not found. Pass -MavenCommand explicitly, add mvn.cmd to PATH or set IDEA_MAVEN_HOME.'
}

$fastTests = @(
    'UniDrDefinitionTest',
    'UniDrDefinitionV2Test',
    'UniDrDefinitionJsonCodecTest',
    'UniDrAppearanceTest',
    'UniDrQueryAdapterTest',
    'UniDrQueryServiceTest',
    'UniDrRunParameterResolverTest',
    'UniDrRunServiceTest',
    'UniDrFileStoreTest'
) -join ','

# Same selection as the 2026-10-24 targeted run: 325 tests, 5 known gate failures
# (ReportFormSeamTest x3, WorkspaceManifestTest x2) plus the slow rendering,
# Postgres and spike classes.
$fullTests = @(
    'UniDr*Test',
    'ReportCatalog*Test',
    'ContextualReportLauncherTest',
    'ReportPrintDialogUniDrTest',
    'ReportFormSeamTest',
    'PlatformSubsystemClosureTest',
    'WorkspaceManifestTest'
) -join ','

if ($TestPattern) { $selection = $TestPattern }
elseif ($Full) { $selection = $fullTests }
else { $selection = $fastTests }

Push-Location $projectDirectory
try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    $maven = Resolve-MavenCommand $MavenCommand
    $total = [System.Diagnostics.Stopwatch]::StartNew()

    # Reinstalling the module jar makes Maven recompile every application test
    # source (325 files, ~30s). Only install when module sources actually moved;
    # otherwise the application compile stays incremental.
    $installedJar = Join-Path $env:USERPROFILE '.m2/repository/org/ipro/platform-unidr/1.0-SNAPSHOT/platform-unidr-1.0-SNAPSHOT.jar'
    $moduleSources = @(Get-Item -LiteralPath 'platform-unidr/pom.xml')
    $moduleSources += @(Get-ChildItem -LiteralPath 'platform-unidr/src/main' -Recurse -File)
    $newestSource = ($moduleSources | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
    $needsInstall = $ForceInstall -or -not (Test-Path -LiteralPath $installedJar) -or
        $newestSource -gt (Get-Item -LiteralPath $installedJar).LastWriteTime

    if ($needsInstall) {
        Write-Host '[1/3] platform-unidr: install with tests'
    } else {
        Write-Host '[1/3] platform-unidr: test (installed jar is current)'
    }
    $step = [System.Diagnostics.Stopwatch]::StartNew()
    if ($needsInstall) {
        & $maven -o -q -f platform-unidr/pom.xml install
    } else {
        & $maven -o -q -f platform-unidr/pom.xml test
    }
    if ($LASTEXITCODE -ne 0) { throw "platform-unidr check failed (exit $LASTEXITCODE)" }
    Write-Host ("      {0:n1}s" -f $step.Elapsed.TotalSeconds)

    # Maven's testCompile recompiles all 325 test sources whenever it decides the
    # module changed (~30-60s). Skip the lifecycle phase entirely while every
    # source/resource is older than the newest compiled test class.
    $testClasses = 'target/test-classes'
    $appInputs = @(Get-Item -LiteralPath 'pom.xml')
    $appInputs += @(Get-ChildItem -LiteralPath 'src' -Recurse -File)
    $newestInput = ($appInputs | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
    $newestClass = if (Test-Path -LiteralPath $testClasses) {
        Get-ChildItem -LiteralPath $testClasses -Recurse -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    }
    $needsCompile = $ForceCompile -or $Full -or -not $newestClass -or $newestInput -gt $newestClass.LastWriteTime

    if ($SkipCompile) {
        Write-Host '[2/3] application test-compile: skipped'
    } elseif ($needsCompile) {
        Write-Host '[2/3] application test-compile'
        $step.Restart()
        & $maven -o -q test-compile
        if ($LASTEXITCODE -ne 0) { throw "application test-compile failed (exit $LASTEXITCODE)" }
        Write-Host ("      {0:n1}s" -f $step.Elapsed.TotalSeconds)
    } else {
        Write-Host '[2/3] application test-compile: up to date'
    }

    Write-Host "[3/3] surefire:test ($(if ($TestPattern) { $TestPattern } elseif ($Full) { 'full targeted set' } else { 'fast unit set' }))"
    $step.Restart()
    & $maven -o -q surefire:test "-Dtest=$selection" '-Dsurefire.failIfNoSpecifiedTests=false'
    $testExit = $LASTEXITCODE
    Write-Host ("      {0:n1}s" -f $step.Elapsed.TotalSeconds)

    Write-Host ("Total: {0:n1}s" -f $total.Elapsed.TotalSeconds)
    exit $testExit
} finally {
    Pop-Location
}
