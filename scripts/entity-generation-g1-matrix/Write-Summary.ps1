[CmdletBinding()]
param([Parameter(Mandatory)][string]$RunDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$matrixRun = [IO.Path]::GetFullPath($RunDirectory)
$matrixReceipt = Get-Content -Raw "$matrixRun/input/plan-receipt.json" | ConvertFrom-Json
[xml]$matrixP8 = Get-Content -Raw "$matrixRun/p8-test.xml"
if ([int]$matrixP8.testsuite.tests -ne 1 -or [int]$matrixP8.testsuite.errors -ne 0 -or [int]$matrixP8.testsuite.failures -ne 0 -or [int]$matrixP8.testsuite.skipped -ne 0) { throw 'P8 evidence is not successful' }
$matrixCSummaryPath = Join-Path $matrixRun 'c/matrix/summary.json'
$matrixC = Get-Content -Raw $matrixCSummaryPath | ConvertFrom-Json
if ($matrixC.kind -ne 'g1-c-summary-v1' -or $matrixC.mode -ne 'generated-acceptance' -or $matrixC.status -ne 'success' -or $matrixC.plan.planId -ne $matrixReceipt.planId -or $matrixC.source.sha256 -ne $matrixReceipt.sha256 -or $matrixC.tests.run -ne 5 -or $matrixC.tests.failures -or $matrixC.tests.errors -or $matrixC.tests.skipped) { throw 'C evidence does not match this Matrix plan' }
foreach ($matrixPair in @(@('applied','APPLIED'),@('noop','NOOP'),@('conflict','CONFLICT'))) {
    $matrixApply = Get-Content -Raw "$matrixRun/$($matrixPair[0]).json" | ConvertFrom-Json
    if ($matrixApply.status -ne $matrixPair[1] -or $matrixApply.planId -ne $matrixReceipt.planId) { throw "Unexpected apply evidence: $($matrixPair[0])" }
}
$matrixTarget = Join-Path "$matrixRun/project/src/main/java" $matrixReceipt.relativePath
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $matrixTarget).Hash.ToLowerInvariant() -ne $matrixReceipt.sha256) { throw 'Applied source differs from reviewed P8 source' }
$matrixConflict = Join-Path "$matrixRun/conflict-project/src/main/java" $matrixReceipt.relativePath
if (-not [IO.File]::ReadAllText($matrixConflict).EndsWith("// User edit retained by conflict probe`n")) { throw 'Conflict did not preserve user edit' }
$matrixReports = @(Get-ChildItem -LiteralPath "$matrixRun/project/target/surefire-reports" -Filter 'TEST-*.xml' | ForEach-Object {
    [xml]$matrixReport = Get-Content -Raw -LiteralPath $_.FullName
    [pscustomobject][ordered]@{suite=$matrixReport.testsuite.name; tests=[int]$matrixReport.testsuite.tests; failures=[int]$matrixReport.testsuite.failures; errors=[int]$matrixReport.testsuite.errors; skipped=[int]$matrixReport.testsuite.skipped}
})
if ($matrixReports.Count -ne 2 -or ($matrixReports | Measure-Object -Property tests -Sum).Sum -ne 8 -or ($matrixReports | Where-Object { $_.failures -or $_.errors -or $_.skipped })) { throw 'Unexpected applied-project test results' }
$matrixResult = [ordered]@{status='success'; scenario='matrix'; planId=$matrixReceipt.planId; sourceSha256=$matrixReceipt.sha256; relativePath=$matrixReceipt.relativePath; p8Tests=1; cSummary=$matrixCSummaryPath; cTests=5; apply='APPLIED'; repeat='NOOP'; conflict='CONFLICT_MODIFIED'; runtime=$matrixReports; schema='isolated H2 create-drop; production migration not accepted'; browserRun=$false}
[IO.File]::WriteAllText("$matrixRun/scenario-summary.json", ($matrixResult | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output "Matrix scenario SUCCESS: $matrixRun"
