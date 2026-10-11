Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'platform-consumer-gate-lib.ps1')
$manifest = Get-Content (Join-Path $PSScriptRoot 'platform-consumer-cases.json') -Raw | ConvertFrom-Json
$directory = Join-Path (Split-Path $PSScriptRoot) ('outputs/consumer-gate-contract/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$results = @()
function Check([string]$Name, [string[]]$Names, [string]$Child = '', [bool]$ExpectedPass = $false, [bool]$NoReport = $false) {
    $caseDirectory = Join-Path $directory $Name
    New-Item -ItemType Directory -Path $caseDirectory | Out-Null
    if (-not $NoReport) {
        $cases = for ($i=0; $i -lt $Names.Count; $i++) {
            '<testcase classname="Fixture" name="' + [Security.SecurityElement]::Escape($Names[$i]) + '">' +
                $(if ($i -eq 0) { $Child }) + '</testcase>'
        }
        # Child nodes must fail even if a broken reporter claims zero failures/skips.
        ('<testsuite tests="' + $Names.Count + '" failures="0" errors="0" skipped="0">' +
            ($cases -join '') + '</testsuite>') | Set-Content (Join-Path $caseDirectory 'TEST-fixture.xml') -Encoding utf8
    }
    $passed=$true; $reason=''
    try { $null=@(Assert-ConsumerJUnit $caseDirectory) } catch { $passed=$false; $reason=$_.Exception.Message }
    if ($passed -ne $ExpectedPass) { throw "Unexpected gate contract result: $Name" }
    $script:results += [ordered]@{case=$Name;expectedPass=$ExpectedPass;passed=$passed;reason=$reason}
}
Check 'valid-manifest' @($manifest.requiredCases) -ExpectedPass $true
Check 'no-reports' @() -NoReport $true
Check 'zero-cases' @()
Check 'missing-case' @($manifest.requiredCases | Select-Object -Skip 1)
Check 'duplicate-case' (@($manifest.requiredCases | Select-Object -Skip 1) + @($manifest.requiredCases[1]))
Check 'skipped-child' @($manifest.requiredCases) '<skipped/>'
Check 'failed-child' @($manifest.requiredCases) '<failure message="fixture"/>'
Check 'error-child' @($manifest.requiredCases) '<error message="fixture"/>'
Check 'renamed-case' (@($manifest.requiredCases | Select-Object -Skip 1) + @('unreviewedReplacement'))
$results | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $directory 'results.json') -Encoding utf8
Write-Host "PASS: $($results.Count) gate contract cases; evidence: $directory"
