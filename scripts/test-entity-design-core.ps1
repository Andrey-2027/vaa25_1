#requires -Version 7.0
[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository',
    [string]$OutputDirectory = 'outputs/entity-explorer-p8-1'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$coreRoot = Split-Path $PSScriptRoot -Parent
$coreOutput = Join-Path $coreRoot $OutputDirectory
& "$PSScriptRoot/test-entity-design-contract.ps1" -JavaHome $JavaHome -Maven $Maven -LocalRepository $LocalRepository -OutputDirectory $OutputDirectory
$reports = @(Get-ChildItem -LiteralPath "$coreRoot/entity-design-core/target/surefire-reports" -Filter 'TEST-*.xml')
$tests = 0; $failures = 0; $errors = 0; $skipped = 0
foreach ($report in $reports) {
    [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
    $tests += [int]$xml.testsuite.tests; $failures += [int]$xml.testsuite.failures
    $errors += [int]$xml.testsuite.errors; $skipped += [int]$xml.testsuite.skipped
}
if ($tests -eq 0 -or $failures -gt 0 -or $errors -gt 0 -or $skipped -gt 0) { throw 'Expected passing core tests without skips.' }
$dependencies = @(& (Join-Path $JavaHome 'bin/jdeps.exe') --multi-release 21 --recursive -s "$coreRoot/entity-design-core/target/entity-design-core-1.0-SNAPSHOT.jar" 2>&1)
if ($LASTEXITCODE -ne 0) { throw "jdeps failed: $dependencies" }
[IO.File]::WriteAllLines("$coreOutput/runtime-dependencies.log", [string[]]$dependencies)
if ($dependencies.Count -eq 0 -or @($dependencies | Where-Object { $_ -notmatch '\s->\s+java\.(base|compiler)$' }).Count -gt 0) {
    throw 'Core must depend only on JDK base/compiler; see runtime-dependencies.log.'
}
$result = [ordered]@{tests=$tests;failures=$failures;errors=$errors;skipped=$skipped;runtimeDependencies=$dependencies;generationEnabled=$false}
[IO.File]::WriteAllText("$coreOutput/core-results.json", ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output "P8.1 core checks passed: $tests tests; runtime dependencies limited to JDK."
