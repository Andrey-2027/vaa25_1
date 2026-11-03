[CmdletBinding()]
param(
    [string]$OutputRoot = '',
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = (Join-Path $env:USERPROFILE '.m2/repository'),
    [switch]$SkipModuleInstall
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
if (-not $OutputRoot) { $OutputRoot = Join-Path $repo ('outputs/register-g1/g1r/g1r4/' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$verificationPom = Join-Path $repo ('.g1r4-' + [Guid]::NewGuid().ToString('N') + '.pom.xml')
$oldJava = $env:JAVA_HOME
$summary = [ordered]@{package='G1R.4 components'; status='FAIL'; dbScope='H2'; browser='NOT_RUN - G1R.5';
    postgresql='NOT_RUN'; modules=[ordered]@{}; suites=@(); output=$OutputRoot}
function Save-Summary {
    [IO.File]::WriteAllText((Join-Path $OutputRoot 'summary.json'), ($summary | ConvertTo-Json -Depth 15) + "`n")
}
Push-Location -LiteralPath $repo
try {
    $env:JAVA_HOME = $JavaHome
    if (-not $SkipModuleInstall) {
        foreach ($module in @('entity-design-json','entity-design-runtime','platform-register-vaadin')) {
            & $Maven -o "-Dmaven.repo.local=$LocalRepository" -f "$module/pom.xml" install *> "$OutputRoot/$module-install.log"
            $summary.modules[$module] = [ordered]@{exitCode=$LASTEXITCODE;log="$module-install.log"}
            if ($LASTEXITCODE -ne 0) { throw "Module install failed: $module" }
        }
    }
    $pom = [IO.File]::ReadAllText((Join-Path $repo 'pom.xml'))
    $build = $pom.IndexOf('<build>')
    if ($build -lt 0) { throw 'Application build section missing' }
    $pom = $pom.Insert($build + '<build>'.Length, '<directory>' + [Security.SecurityElement]::Escape((Join-Path $OutputRoot 'build')) + '</directory>')
    [IO.File]::WriteAllText($verificationPom, $pom)
    $tests = @('DraftEditorSessionTest','EntityDesignRuntimeProjectionIT','NomenclaturePriceFormTest',
        'IndependentNoneRegisterFormTest','RegisterListFormToolbarTest') + @(
        Get-ChildItem -LiteralPath 'src/test/java' -Recurse -Filter 'EntityDraft*Test.java' -File | ForEach-Object BaseName)
    $tests = @($tests | Sort-Object -Unique)
    $started = [DateTime]::UtcNow
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" -f $verificationPom '-Dvaadin.skip=true' `
        "-Ddesign.g1r4.output=$OutputRoot/downloads" "-Dtest=$($tests -join ',')" test *> "$OutputRoot/application.log"
    $summary['exitCode'] = $LASTEXITCODE
    if ($LASTEXITCODE -ne 0) { throw 'P8/forms application tests failed; see application.log' }
    $reports = Join-Path $OutputRoot 'build/surefire-reports'
    $rows = foreach ($test in $tests) {
        $report = @(Get-ChildItem -LiteralPath $reports -Filter "TEST-*.$test.xml" -File)
        if ($report.Count -ne 1 -or $report[0].LastWriteTimeUtc -lt $started) { throw "Missing/stale suite: $test" }
        [xml]$xml = Get-Content -LiteralPath $report[0].FullName -Raw
        $suite = $xml.testsuite
        if ([int]$suite.tests -eq 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "Suite did not pass without skips: $test"
        }
        [ordered]@{name=$test;tests=[int]$suite.tests;failures=[int]$suite.failures;errors=[int]$suite.errors;skipped=[int]$suite.skipped}
    }
    $summary.suites = @($rows)
    $summary['tests'] = ($rows | ForEach-Object { $_.tests } | Measure-Object -Sum).Sum
    $artifacts = foreach ($artifact in @('entity-design-core','entity-design-json','entity-design-runtime','platform-register-vaadin')) {
        $path = Join-Path $LocalRepository "org/ipro/$artifact/1.0-SNAPSHOT/$artifact-1.0-SNAPSHOT.jar"
        [ordered]@{artifact=$artifact;path=$path;sha256=(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $summary['artifacts'] = @($artifacts)
    $summary.status = 'PASS'
} catch {
    $summary['error'] = $_.Exception.Message
    throw
} finally {
    Save-Summary
    if (Test-Path -LiteralPath $verificationPom) { Remove-Item -LiteralPath $verificationPom }
    $env:JAVA_HOME = $oldJava
    Pop-Location
}
Write-Host "G1R.4 components: $($summary.status), $($summary.tests) tests; $OutputRoot"
