[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$checks = [System.Collections.Generic.List[object]]::new()
function Check([string]$Name, [bool]$Passed) {
    $checks.Add([pscustomobject]@{ name = $Name; passed = $Passed })
    if (-not $Passed) { throw "Evidence verification failed: $Name" }
}
function Hash([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$summary = Get-Content "$root/selftest-summary.json" -Raw | ConvertFrom-Json
Check '87 unique D selftests; no failures or skips' ($summary.status -eq 'success' -and
    $summary.cases -eq 87 -and $summary.passed -eq 87 -and $summary.failed -eq 0 -and
    $summary.skipped -eq 0 -and @($summary.results.name | Sort-Object -Unique).Count -eq 87 -and
    @($summary.results | Where-Object status -ne 'PASS').Count -eq 0)
$new = @($summary.results | Where-Object name -like 'three-file-*')
Check '12 three-file cases; previous 75 remain distinct' ($new.Count -eq 12)
$proofs = @(Get-ChildItem "$root/proofs/*.json")
Check 'one proof per new case' ($proofs.Count -eq 12)
foreach ($file in $proofs) {
    $proof = Get-Content $file.FullName -Raw | ConvertFrom-Json
    $project = Join-Path "$root/projects" (Split-Path $proof.projectRoot -Leaf)
    $plan = Get-Content "$project/reviewed-plan.json" -Raw | ConvertFrom-Json
    $c = Get-Content "$project/c-summary.json" -Raw | ConvertFrom-Json
    $case = @($new | Where-Object name -eq "three-file-$($file.BaseName)")
    Check "$($file.BaseName): proof agrees with successful case outcome" ($case.Count -eq 1 -and
        $case[0].actual -eq "$($proof.status)/$($proof.code)")
    Check "$($file.BaseName): three generated files and two sections" ($plan.files.Count -eq 3 -and
        $plan.source.sections.Count -eq 2 -and $proof.manifest.entries.Count -eq 3)
    Check "$($file.BaseName): plan/manifest/C binding" ($plan.planId -eq $proof.planId -and
        $c.manifest.planId -eq $proof.planId -and
        $c.manifest.manifestSha256 -eq $proof.manifest.manifestSha256 -and
        (Hash "$project/c-summary.json") -eq $proof.cSummarySha256)
    foreach ($entry in $proof.manifest.entries) {
        $generated = @($plan.files | Where-Object relativePath -eq $entry.relativePath)
        $bytes = [Text.Encoding]::UTF8.GetBytes($generated[0].content)
        $sha = [Security.Cryptography.SHA256]::Create()
        try { $actualHash = [BitConverter]::ToString($sha.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
        Check "$($file.BaseName): reviewed $($entry.relativePath)" ($generated.Count -eq 1 -and
            $generated[0].sha256 -eq $entry.sha256 -and
            $actualHash -eq $entry.sha256 -and $bytes.Length -eq $entry.bytes)
    }
    $recorded = @($proof.filesAfterAssertions.PSObject.Properties)
    foreach ($item in $recorded) {
        Check "$($file.BaseName): archived $($item.Name)" ((Hash (Join-Path $project $item.Name)) -eq $item.Value)
    }
    Check "$($file.BaseName): complete archived snapshot" (
        @(Get-ChildItem -LiteralPath $project -Recurse -File -Force).Count -eq $recorded.Count)
}
$sources = Get-Content "$root/source-hashes.json" -Raw | ConvertFrom-Json
foreach ($source in $sources) {
    Check "source snapshot: $($source.path)" ((Hash (Join-Path $root $source.snapshot)) -eq $source.sha256)
}
$checks | ConvertTo-Json -Depth 5 | Set-Content "$root/verification.json" -Encoding utf8
Write-Output "Verified $($checks.Count) evidence checks; 87/87 D selftests, 12/12 new cases."
