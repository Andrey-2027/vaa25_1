[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$GeneratedRoot,
    [string]$OutputRoot = '',
    [string]$LocalRepository = (Join-Path $env:USERPROFILE '.m2/repository'),
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$GeneratedRoot = (Resolve-Path -LiteralPath $GeneratedRoot).Path
if (-not $OutputRoot) { $OutputRoot = Join-Path $repo ('outputs/register-g1/g1r/g1r3/c-' + (Get-Date -Format 'yyyyMMdd-HHmmss')) }
$OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$launcher = Join-Path $PSScriptRoot 'test-entity-generation-g1.ps1'
$hostExe = (Get-Process -Id $PID).Path
$checks = [ordered]@{}
$results = [ordered]@{}
function Save-Json($path, $value) {
    [IO.File]::WriteAllText($path, ($value | ConvertTo-Json -Depth 30) + "`n", [Text.UTF8Encoding]::new($false))
}
function Run-C([string]$id, [string]$source, [string]$manifest, [string]$spec,
        [string]$expected = '', [string]$receipt = '') {
    $inputArgs = @('-SourceDirectory', $source)
    if ($receipt) {
        $entry = (Get-Content -LiteralPath $manifest -Raw | ConvertFrom-Json).entries[0]
        $inputArgs = @('-SourceFile', (Join-Path $source $entry.relativePath), '-RelativePath', $entry.relativePath,
            '-ExpectedSha256', $entry.sha256, '-PlanReceipt', $receipt)
    }
    & $hostExe -NoProfile -File $launcher @inputArgs -ManifestFile $manifest `
        -SpecFile $spec -OutputDirectory $OutputRoot -RunId $id -LocalRepository $LocalRepository `
        -JavaHome $JavaHome -Maven $Maven *> (Join-Path $OutputRoot ($id + '.log'))
    $exitCode = $LASTEXITCODE
    $summary = Get-Content -LiteralPath (Join-Path $OutputRoot "$id/summary.json") -Raw | ConvertFrom-Json
    $pass = if ($expected) { $exitCode -ne 0 -and $summary.failureReason -eq $expected } else {
        $exitCode -eq 0 -and $summary.status -eq 'success' -and $summary.tests.run -eq 6
    }
    if ($id -eq 'reject-runtime-spec') {
        $pass = $pass -and $summary.tests.run -gt 0 -and $summary.tests.failures -gt 0
    }
    $checks[$id] = $pass
    $results[$id] = [ordered]@{ passed = $pass; exitCode = $exitCode; summary = "$id/summary.json"; expectedFailure = $expected }
}
foreach ($variant in @('none', 'day')) {
    $inputRoot = Join-Path $GeneratedRoot $variant
    $archived = Join-Path $OutputRoot "inputs/$variant"
    New-Item -ItemType Directory -Path $archived -Force | Out-Null
    foreach ($name in @('draft.json', 'context.json', 'plan.json', 'manifest.json', 'receipt.json')) {
        Copy-Item -LiteralPath (Join-Path $inputRoot $name) -Destination $archived
    }
    $spec = Join-Path $PSScriptRoot "entity-generation-g1/scenarios/register-$variant/ScenarioSpec.java"
    if ($variant -eq 'day') {
        $context = Get-Content -LiteralPath (Join-Path $inputRoot 'context.json') -Raw | ConvertFrom-Json
        if ($context.referenceTargets.'com.example.Item'.sourceTypeName -ne 'com.example.Item' -or
            $context.referenceTargets.'com.example.Item'.eligibility -ne 'SUPPORTED') {
            throw 'DAY input must contain a valid consumer-owned com.example.Item reference target'
        }
    }
    Run-C $variant (Join-Path $inputRoot 'source') (Join-Path $inputRoot 'manifest.json') $spec
}
$none = Join-Path $GeneratedRoot 'none'
$noneSpec = Join-Path $PSScriptRoot 'entity-generation-g1/scenarios/register-none/ScenarioSpec.java'
$mutations = Join-Path $OutputRoot 'mutations'
New-Item -ItemType Directory -Path $mutations -Force | Out-Null
foreach ($field in @('recipe', 'generator', 'profile', 'hash')) {
    $manifest = Get-Content -LiteralPath (Join-Path $none 'manifest.json') -Raw | ConvertFrom-Json
    switch ($field) {
        recipe { $manifest.recipe.version = 2 }
        generator { $manifest.generatorVersion = 'register-java-v999' }
        profile { $manifest.fingerprintProfile = 'plan-fingerprint-v2' }
        hash { $manifest.entries[0].sha256 = '0' * 64 }
    }
    $file = Join-Path $mutations "$field.json"
    Save-Json $file $manifest
    $reason = if ($field -eq 'hash') { 'manifest-mismatch' } else { 'manifest-invalid' }
    Run-C "reject-$field" (Join-Path $none 'source') $file $noneSpec $reason
}
$modified = Join-Path $mutations 'modified-source'
Copy-Item -LiteralPath (Join-Path $none 'source') -Destination $modified -Recurse
[IO.File]::AppendAllText((Join-Path $modified 'com/example/generated/RegionRate.java'), "// modified`n")
Run-C 'reject-source' $modified (Join-Path $none 'manifest.json') $noneSpec 'manifest-mismatch'
Run-C 'reject-spec' (Join-Path $none 'source') (Join-Path $none 'manifest.json') `
    (Join-Path $PSScriptRoot 'entity-generation-g1/scenarios/empty-catalog/ScenarioSpec.java') 'spec-mismatch'
$receipt = Get-Content -LiteralPath (Join-Path $none 'receipt.json') -Raw | ConvertFrom-Json
$receipt.planId = 'g1-' + ('0' * 64)
$receiptFile = Join-Path $mutations 'receipt.json'
Save-Json $receiptFile $receipt
Run-C 'reject-receipt' (Join-Path $none 'source') (Join-Path $none 'manifest.json') $noneSpec 'plan-receipt-mismatch' $receiptFile
# A well-formed, matching manifest must not turn wrong independent expectations into a pass.
$wrongSpec = Join-Path $mutations 'ScenarioSpec.java'
[IO.File]::WriteAllText($wrongSpec, [IO.File]::ReadAllText($noneSpec).Replace('"region_code"', '"wrong_key"'))
Run-C 'reject-runtime-spec' (Join-Path $none 'source') (Join-Path $none 'manifest.json') $wrongSpec 'maven-failed'
$passed = @($checks.Values | Where-Object { $_ -eq $true }).Count
$summary = [ordered]@{
    package = 'G1R.3 C only'; status = $(if ($passed -eq $checks.Count) { 'PASS' } else { 'FAIL' })
    generatedRoot = $GeneratedRoot; checksPassed = $passed; checksTotal = $checks.Count; results = $results
    notRun = @('D apply/receipt/rollback', 'P8/browser', 'PostgreSQL/migrations', 'full G1R.3 acceptance')
}
Save-Json (Join-Path $OutputRoot 'summary.json') $summary
Write-Host ("Register C: $($summary.status), $passed/$($checks.Count); $OutputRoot")
if ($summary.status -ne 'PASS') { exit 1 }
# The last C run intentionally fails (wrong independent spec). Do not leak its exit code
# to callers after all positive and negative assertions have passed.
exit 0
