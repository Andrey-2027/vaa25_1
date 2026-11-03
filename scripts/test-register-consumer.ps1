[CmdletBinding()]
param(
    [string]$JavaHome = '',
    [string]$Maven = '',
    [string]$Repository = '',
    [string]$OutputRoot = '',
    [ValidateSet('h2', 'postgresql')]
    [string]$DbProfile = 'h2',
    [switch]$SkipPlatformInstall
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$taskRoot = [IO.Path]::GetFullPath((Split-Path $PSScriptRoot -Parent))
$project = Join-Path $taskRoot 'examples/register-consumer'
if (-not (Test-Path -LiteralPath (Join-Path $project 'pom.xml'))) {
    throw "register-consumer project not found: $project"
}

if ([string]::IsNullOrWhiteSpace($Maven)) {
    $candidates = @(
        'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
        'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2\plugins\maven\lib\maven3\bin\mvn.cmd',
        (Join-Path $env:USERPROFILE '.m2\wrapper\dists\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd')
    )
    $Maven = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace($Maven) -or -not (Test-Path -LiteralPath $Maven)) {
    throw 'Maven not found. Pass -Maven <path to mvn.cmd>.'
}

if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    if (Test-Path -LiteralPath 'C:\Program Files\Axiom\AxiomJDK-21\bin\java.exe') {
        $JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21'
    } elseif ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
        $JavaHome = $env:JAVA_HOME
    }
}
if ([string]::IsNullOrWhiteSpace($JavaHome) -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw 'Java 21 home not found. Pass -JavaHome <path>.'
}
$JavaHome = $JavaHome.TrimEnd('\')
$releaseFile = Join-Path $JavaHome 'release'
if (-not (Test-Path -LiteralPath $releaseFile)) {
    throw "Java release file not found in $JavaHome. Pass -JavaHome <JDK 21>."
}
$release = Get-Content -LiteralPath $releaseFile -Raw
if ($release -notmatch 'JAVA_VERSION="21') {
    throw "Java 21 is required, found: $release. Pass -JavaHome <JDK 21>."
}

if ([string]::IsNullOrWhiteSpace($Repository)) {
    $homeRepository = Join-Path $env:USERPROFILE '.m2\repository'
    if (Test-Path -LiteralPath (Join-Path $homeRepository 'org/ipro/platform-backend-starter')) {
        $Repository = $homeRepository
    } else {
        $Repository = Join-Path $taskRoot '.local-maven-repository'
    }
}
$Repository = [IO.Path]::GetFullPath($Repository)
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $taskRoot 'outputs/register-g1/g1r/g1r1'
}
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$runOutput = Join-Path ([IO.Path]::GetFullPath($OutputRoot)) $runId
New-Item -ItemType Directory -Force -Path $runOutput | Out-Null

$work = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('register-consumer-' + [Guid]::NewGuid().ToString('N'))))
if ($work.StartsWith($taskRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The isolated consumer build must live outside the checkout.'
}
New-Item -ItemType Directory -Force -Path $work | Out-Null
Copy-Item -LiteralPath (Join-Path $project 'pom.xml') -Destination $work
Copy-Item -LiteralPath (Join-Path $project 'src') -Destination $work -Recurse

$started = Get-Date
$oldJava = $env:JAVA_HOME
$oldPassword = $env:REGISTER_CONSUMER_DEMO_PASSWORD
$oldSpringProfiles = $env:SPRING_PROFILES_ACTIVE
$exitCode = 1
$installExit = 0
$log = Join-Path $runOutput 'maven-test.log'
$installLog = Join-Path $runOutput 'platform-install.log'

try {
    $env:JAVA_HOME = $JavaHome
    $env:REGISTER_CONSUMER_DEMO_PASSWORD = 'register-consumer-launcher-only'
    if ($DbProfile -eq 'postgresql') {
        $env:SPRING_PROFILES_ACTIVE = 'postgresql'
    } else {
        $env:SPRING_PROFILES_ACTIVE = $null
    }

    $installed = Test-Path -LiteralPath (Join-Path $Repository 'org/ipro/platform-backend-starter/1.0-SNAPSHOT')
    if (-not $installed -and -not $SkipPlatformInstall) {
        Push-Location $taskRoot
        try {
            & $Maven -f (Join-Path $taskRoot 'pom.xml') "-Dmaven.repo.local=$Repository" -DskipTests `
                -pl platform-backend-starter -am install *> $installLog
            $installExit = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        if ($installExit -ne 0) {
            throw "Platform install failed with exit code $installExit. See $installLog"
        }
    }

    $dbArgument = "-Dregister.consumer.db.profile=$DbProfile"
    Push-Location $work
    try {
        & $Maven -f (Join-Path $work 'pom.xml') "-Dmaven.repo.local=$Repository" $dbArgument test *> $log
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $reports = Join-Path $work 'target/surefire-reports'
    if (Test-Path -LiteralPath $reports) {
        Copy-Item -LiteralPath $reports -Destination (Join-Path $runOutput 'surefire') -Recurse -Force
    }
    $tree = Join-Path $work 'target/register-consumer-runtime-dependency-tree.json'
    if (Test-Path -LiteralPath $tree) {
        Copy-Item -LiteralPath $tree -Destination (Join-Path $runOutput 'register-consumer-runtime-dependency-tree.json')
    }
} catch {
    $exitCode = if ($exitCode -eq 0) { 1 } else { $exitCode }
    $_ | Out-String | Add-Content -LiteralPath (Join-Path $runOutput 'launcher-error.txt')
} finally {
    $env:JAVA_HOME = $oldJava
    $env:REGISTER_CONSUMER_DEMO_PASSWORD = $oldPassword
    $env:SPRING_PROFILES_ACTIVE = $oldSpringProfiles
}

$tests = 0
$failures = 0
$errors = 0
$skipped = 0
$classes = 0
$reportsDirectory = Join-Path $runOutput 'surefire'
if (Test-Path -LiteralPath $reportsDirectory) {
    foreach ($report in Get-ChildItem -LiteralPath $reportsDirectory -Filter 'TEST-*.xml' -File) {
        [xml]$result = Get-Content -LiteralPath $report.FullName -Raw
        $suite = $result.testsuite
        $classes++
        $tests += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        $skipped += [int]$suite.skipped
    }
}

$status = if ($exitCode -eq 0 -and $tests -gt 0 -and $failures -eq 0 -and $errors -eq 0 -and $skipped -eq 0) { 'PASS' } else { 'FAIL' }
$summary = [ordered]@{
    package         = 'G1R.1 / A2.1'
    runId           = $runId
    startedAt       = $started.ToString('o')
    finishedAt      = (Get-Date).ToString('o')
    dbProfile       = $DbProfile
    status          = $status
    exitCode        = $exitCode
    platformInstallExit = $installExit
    javaHome        = $JavaHome
    maven           = $Maven
    repository      = $Repository
    isolatedBuild   = $work
    output          = $runOutput
    testClasses     = $classes
    tests           = $tests
    failures        = $failures
    errors          = $errors
    skipped         = $skipped
    h2Accepted      = ($DbProfile -eq 'h2' -and $status -eq 'PASS')
    postgresql      = if ($DbProfile -eq 'postgresql') { 'NOT_RUN: separate DB gate (R7/REG-A3)' } else { 'NOT_RUN: declared profile only' }
    runtimeDependencyTree = (Test-Path -LiteralPath (Join-Path $runOutput 'register-consumer-runtime-dependency-tree.json'))
    notes           = 'Manual A2.1 declarations; they are not a generator result. Browser/UI/query scope is out of this package.'
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $runOutput 'summary.json') -Encoding utf8

Write-Output "G1R.1 A2.1: $status (exit $exitCode), tests=$tests failures=$failures errors=$errors skipped=$skipped"
Write-Output "Evidence: $runOutput"
if ($exitCode -ne 0) {
    if (Test-Path -LiteralPath $log) { Get-Content -LiteralPath $log -Tail 60 }
    exit $exitCode
}
