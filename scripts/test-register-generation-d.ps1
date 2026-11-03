[CmdletBinding()]
param(
    [string]$Baseline = 'outputs/register-g1/g1r/g1r2-completion-20261102',
    [string]$OutputRoot = '',
    [string]$LocalRepository = (Join-Path $env:USERPROFILE '.m2/repository'),
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$Baseline = (Resolve-Path -LiteralPath $Baseline).Path
$coreJar = Join-Path $Baseline 'artifacts/entity-design-core-1.0-SNAPSHOT.jar'
$baselineSummary = Get-Content -LiteralPath (Join-Path $Baseline 'summary.json') -Raw | ConvertFrom-Json
if ((Get-FileHash -LiteralPath $coreJar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $baselineSummary.coreJar.sha256) {
    throw 'Immutable core artifact differs from the accepted G1R.2 baseline'
}
if (-not $OutputRoot) { $OutputRoot = Join-Path $repo ('outputs/register-g1/g1r/g1r3/d-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$launcher = Join-Path $PSScriptRoot 'apply-entity-generation-g1.ps1'
$hostExe = (Get-Process -Id $PID).Path
$template = Join-Path $PSScriptRoot 'entity-generation-g1/register-consumer'
$summary = [ordered]@{ package='G1R.3 actual D'; status='FAIL'; baseline=$Baseline; coreSha256=$baselineSummary.coreJar.sha256;
    routes=[ordered]@{}; notRun=@('P8/browser - G1R.4/G1R.5', 'PostgreSQL/migrations', 'persistent DB restart acceptance - G1R.5') }
$oldJava = $env:JAVA_HOME
$oldPath = $env:PATH
function Save-Json($path, $value) {
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 25) + "`n", [Text.UTF8Encoding]::new($false))
}
function Maven-Check([string]$project, [string]$log, [string[]]$goals) {
    & $Maven -B -ntp -o -f (Join-Path $project 'pom.xml') "-Dmaven.repo.local=$LocalRepository" @goals *> $log
    if ($LASTEXITCODE -ne 0) { throw "Maven failed ($LASTEXITCODE): $log" }
}
function Run-D([string]$variant, [string]$stage, [string]$project, [string]$cSummary,
        [string]$plan, [string]$tree, [string]$expected) {
    $run = "$variant-$stage"
    & $hostExe -NoProfile -File $launcher -Apply -SkipCoreBuild -CoreClasses $coreJar -ProjectRoot $project `
        -CSummary $cSummary -ReviewedPlan $plan -DependencyTree $tree -LocalRepository $LocalRepository `
        -JavaHome $JavaHome -Maven $Maven -OutputDirectory $OutputRoot -RunId $run *> (Join-Path $OutputRoot "$run.log")
    $exitCode = $LASTEXITCODE
    $result = Get-Content -LiteralPath (Join-Path $OutputRoot "$run/apply-result.json") -Raw | ConvertFrom-Json
    if ($result.status -ne $expected -or $exitCode -ne $result.exitCode -or
            ($expected -in @('APPLIED','NOOP') -and $exitCode -ne 0) -or
            ($expected -eq 'CONFLICT' -and $result.code -ne 'CONFLICT_MODIFIED')) {
        throw "Unexpected D result for ${run}: $($result.status)/$($result.code), exit=$exitCode"
    }
    return $result
}
try {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
    foreach ($variant in @('none','day')) {
        $evidence = Join-Path $OutputRoot $variant
        New-Item -ItemType Directory -Path $evidence -Force | Out-Null
        $project = Join-Path ([IO.Path]::GetTempPath()) ('register-d-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $project | Out-Null
        Copy-Item -Path (Join-Path $template '*') -Destination $project -Recurse
        $spec = Join-Path $PSScriptRoot "entity-generation-g1/scenarios/register-$variant/ScenarioSpec.java"
        Copy-Item -LiteralPath $spec -Destination (Join-Path $project 'src/test/java/com/example/generated/harness/ScenarioSpec.java')
        $planFile = Join-Path $Baseline "generated/$variant/plan.json"
        $plan = Get-Content -LiteralPath $planFile -Raw | ConvertFrom-Json
        $cSummary = Join-Path $Baseline "c/$variant/summary.json"
        $c = Get-Content -LiteralPath $cSummary -Raw | ConvertFrom-Json
        if ($c.status -ne 'success' -or $c.mode -ne 'generated-acceptance' -or $c.plan.planId -ne $plan.planId) {
            throw "A matching successful actual C summary is required for $variant"
        }
        if ((Get-FileHash -LiteralPath $spec -Algorithm SHA256).Hash.ToLowerInvariant() -ne $c.scenario.specSha256) {
            throw "Independent spec changed since C for $variant"
        }
        foreach ($support in $c.supportSources) {
            $supportPath = Join-Path $project $support.relativePath
            if ((Get-FileHash -LiteralPath $supportPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $support.sha256) {
                throw "Consumer support source differs from C: $($support.relativePath)"
            }
        }
        Copy-Item -LiteralPath $planFile -Destination "$evidence/plan.json"
        Copy-Item -LiteralPath $cSummary -Destination "$evidence/c-summary.json"
        $target = Join-Path $project ('src/main/java/' + $plan.files[0].relativePath)
        if ((Test-Path -LiteralPath $target) -or (Test-Path -LiteralPath (Join-Path $project 'target'))) {
            throw 'Clean consumer contains the target source or old build output'
        }
        $tree = Join-Path $project 'target/consumer-runtime-dependency-tree.json'
        Maven-Check $project "$evidence/dependencies.log" @('dependency:tree', '-Dscope=runtime', '-DoutputType=json', "-DoutputFile=$tree")
        $oldClasses = @(Get-ChildItem -LiteralPath $project -Recurse -Filter '*.class' -File)
        if ($oldClasses.Count -ne 0) { throw 'Old class files are present before D' }
        $created = Run-D $variant 'apply' $project $cSummary $planFile $tree 'APPLIED'
        $rollbackProject = Join-Path ([IO.Path]::GetTempPath()) ('register-d-rollback-' + [Guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $rollbackProject | Out-Null
        Copy-Item -Path (Join-Path $template '*') -Destination $rollbackProject -Recurse
        $classes = Join-Path $OutputRoot "$variant-apply/classes"
        $jackson = @('databind','core','annotations') | ForEach-Object {
            Join-Path $LocalRepository "com/fasterxml/jackson/core/jackson-$_/2.18.4/jackson-$_-2.18.4.jar"
        }
        $classpath = (@($classes, $coreJar) + $jackson) -join ';'
        & (Join-Path $JavaHome 'bin/java.exe') -cp $classpath org.ipro.design.apply.RegisterRollbackCheck `
            $rollbackProject $cSummary $planFile (Join-Path $OutputRoot "$variant-apply/current-dependencies.json") `
            "$evidence/rollback-result.json" *> "$evidence/rollback.log"
        if ($LASTEXITCODE -ne 0) { throw "Actual rollback check failed: $evidence/rollback.log" }
        $hash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($hash -ne $plan.files[0].sha256) { throw 'Applied bytes differ from the reviewed output' }
        $receiptHash = (Get-FileHash -LiteralPath $created.receipt -Algorithm SHA256).Hash
        $noop = Run-D $variant 'noop' $project $cSummary $planFile $tree 'NOOP'
        if ((Get-FileHash -LiteralPath $noop.receipt -Algorithm SHA256).Hash -ne $receiptHash) { throw 'NOOP rewrote receipt' }
        Maven-Check $project "$evidence/applied-runtime.log" @('test', '-Dtest=RegisterScenarioTest')
        $reports = @(Get-ChildItem -LiteralPath (Join-Path $project 'target/surefire-reports') -Filter 'TEST-*.xml' -File)
        $tests = 0; $failures = 0; $errors = 0; $skipped = 0
        foreach ($report in $reports) {
            [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
            $tests += [int]$xml.testsuite.tests; $failures += [int]$xml.testsuite.failures
            $errors += [int]$xml.testsuite.errors; $skipped += [int]$xml.testsuite.skipped
        }
        if ($tests -ne 6 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) { throw 'Applied runtime did not pass all six checks' }
        Copy-Item -LiteralPath (Join-Path $project 'src') -Destination "$evidence/src" -Recurse
        Copy-Item -LiteralPath (Join-Path $project 'pom.xml') -Destination $evidence
        Copy-Item -LiteralPath (Join-Path $project 'target/surefire-reports') -Destination "$evidence/reports" -Recurse
        Copy-Item -LiteralPath $tree -Destination $evidence
        Copy-Item -LiteralPath $created.receipt -Destination "$evidence/receipt.json"
        $original = [IO.File]::ReadAllBytes($target)
        [IO.File]::AppendAllText($target, "// injected manual edit for conflict acceptance`n")
        $changedHash = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
        $conflict = Run-D $variant 'conflict' $project $cSummary $planFile $tree 'CONFLICT'
        if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $changedHash) { throw 'D overwrote modified source' }
        Copy-Item -LiteralPath $target -Destination "$evidence/conflicting-source.java.txt"
        # This is our injected test edit; restore the reviewed bytes to leave a usable clean consumer.
        [IO.File]::WriteAllBytes($target, $original)
        $restored = Run-D $variant 'restored-noop' $project $cSummary $planFile $tree 'NOOP'
        if ((Get-FileHash -LiteralPath $created.receipt -Algorithm SHA256).Hash -ne $receiptHash) { throw 'Receipt changed after conflict/noop' }
        if (Test-Path -LiteralPath (Join-Path $project '.g1-apply-v2/journal.json')) { throw 'Unfinished journal after actual apply' }
        $summary.routes[$variant] = [ordered]@{ status='PASS'; project=$project; planId=$plan.planId;
            sourceSha256=$hash; noOldSourceOrClasses=$true; apply=$created.status; repeat=$noop.status;
            actualRollback='PASS'; rollbackProject=$rollbackProject;
            manualEdit=$conflict.code; restored=$restored.status; receiptSha256=$receiptHash.ToLowerInvariant();
            runtime=@{tests=$tests;failures=$failures;errors=$errors;skipped=$skipped;exitCode=0} }
        Save-Json "$evidence/summary.json" $summary.routes[$variant]
    }
    $summary.status = 'PASS'
} catch {
    $summary['error'] = $_.Exception.Message
    throw
} finally {
    $env:JAVA_HOME = $oldJava
    $env:PATH = $oldPath
    Save-Json (Join-Path $OutputRoot 'summary.json') $summary
}
Write-Host "Register actual D: $($summary.status); $OutputRoot"
