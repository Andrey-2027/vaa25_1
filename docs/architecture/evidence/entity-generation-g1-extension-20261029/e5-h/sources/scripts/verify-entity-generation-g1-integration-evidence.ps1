[CmdletBinding()]
param([string]$EvidenceDirectory='docs/architecture/evidence/entity-generation-g1-extension-20261029/e5-h')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$root=[IO.Path]::GetFullPath((Join-Path $repo $EvidenceDirectory))
$checks=[Collections.Generic.List[object]]::new()
function Hash($path){(Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()}
function Check($name,[bool]$passed){$checks.Add([ordered]@{name=$name;passed=$passed});if(!$passed){throw "Archive check failed: $name"}}
$summary=Get-Content "$root/summary.json" -Raw|ConvertFrom-Json
Check 'E5.H passed; browser and migrations remain open' ($summary.stage -eq 'E5.H' -and $summary.status -eq 'passed' -and $summary.browser -eq 'not run; E5.B open' -and $summary.migrations -eq 'not run; M open')
$actual=@(Get-Content "$root/actual-results.json" -Raw|ConvertFrom-Json)
Check '15 unique actual scenarios' ($actual.Count -eq 15 -and @($actual.id|Sort-Object -Unique).Count -eq 15)
foreach($a in $actual){
    $case="$root/actual/$($a.id)"
    $plan=Get-Content "$case/c/input/ScenarioSpec.java" -Raw
    Check "$($a.id) independent spec archived" ($plan.Contains('class ScenarioSpec'))
    $inputTail=($a.input.Replace('\','/') -split '/inputs/',2)[1]
    $reviewed=Get-Content "$root/inputs/$inputTail/reviewed-plan.json" -Raw|ConvertFrom-Json
    $c=Get-Content "$case/c/summary.json" -Raw|ConvertFrom-Json
    $receipt=Get-Content "$case/apply-receipt.json" -Raw|ConvertFrom-Json
    Check "$($a.id) exact independent spec" ((Hash "$case/c/input/ScenarioSpec.java") -eq $c.scenario.specSha256)
    foreach($support in $c.supportSources){
        Check "$($a.id) compiled support source $($support.relativePath)" ((Hash "$case/project/$($support.relativePath)") -eq $support.sha256)
    }
    Check "$($a.id) accepted C binding" ($c.status -eq 'success' -and $c.tests.run -eq $a.cTests -and $c.tests.failures -eq 0 -and $c.tests.errors -eq 0 -and $c.tests.skipped -eq 0 -and $receipt.cSummarySha256 -eq (Hash "$case/c/summary.json"))
    foreach($f in $reviewed.files){
        Check "$($a.id) exact generated/C/applied $($f.relativePath)" ((Hash "$root/inputs/$inputTail/$($f.relativePath)") -eq $f.sha256 -and (Hash "$case/c/input/$($f.relativePath)") -eq $f.sha256 -and (Hash "$case/project/src/main/java/$($f.relativePath)") -eq $f.sha256)
    }
    if($a.id -ne 'matrix'){
        Check "$($a.id) complete manifest/receipt" ($c.manifest.planId -eq $reviewed.planId -and $c.manifest.entries.Count -eq $reviewed.files.Count -and $receipt.entries.Count -eq $reviewed.files.Count -and $receipt.manifest.manifestSha256 -eq $c.manifest.manifestSha256)
        $locations=@(Get-Content "$case/dependencies.json" -Raw|ConvertFrom-Json)
        Check "$($a.id) complete dependency inventory" ($locations.Count -eq $c.dependencies.Count -and $locations.Count -eq $receipt.verifiedDependencies.Count)
        foreach($d in $c.dependencies){Check "$($a.id) receipt dependency $($d.id)" (@($receipt.verifiedDependencies|Where-Object{$_.id -eq $d.id -and $_.sha256 -eq $d.sha256}).Count -eq 1)}
    }
    $count=0
    foreach($file in Get-ChildItem "$case/runtime-reports" -Filter 'TEST-*.xml'){
        [xml]$x=Get-Content $file.FullName -Raw;$s=$x.testsuite;$count += [int]$s.tests
        Check "$($a.id) applied runtime $($s.name)" ([int]$s.tests -gt 0 -and [int]$s.failures -eq 0 -and [int]$s.errors -eq 0 -and [int]$s.skipped -eq 0)
    }
    Check "$($a.id) runtime count" ($count -eq $a.runtimeTests)
    foreach($outcome in @('applied','noop','conflict')){
        $r=Get-Content "$case/$outcome.json" -Raw|ConvertFrom-Json
        Check "$($a.id) D $outcome" ($r.status -eq $outcome.ToUpperInvariant() -and $r.planId -eq $reviewed.planId)
    }
}
foreach($group in @('backend','ui','api')){
    $results=@(Get-Content "$root/$group/test-results.json" -Raw|ConvertFrom-Json)
    foreach($result in $results){
        [xml]$x=Get-Content "$root/$group/reports/TEST-$($result.suite).xml" -Raw;$s=$x.testsuite
        Check "$group archived XML $($result.suite)" ([int]$s.tests -eq $result.tests -and [int]$s.tests -gt 0 -and [int]$s.failures -eq 0 -and [int]$s.errors -eq 0 -and [int]$s.skipped -eq 0)
    }
}
$d=Get-Content "$root/d/selftest-summary.json" -Raw|ConvertFrom-Json
Check 'D selftests 87/87 with no skips' ($d.passed -eq 87 -and $d.failed -eq 0 -and $d.skipped -eq 0 -and @($d.results|Where-Object status -ne 'PASS').Count -eq 0)
$cself=Get-Content "$root/c/selftest-summary.json" -Raw|ConvertFrom-Json
Check 'C selfchecks 12/12' ($cself.status -eq 'passed' -and @($cself.checks.PSObject.Properties).Count -eq 12 -and @($cself.checks.PSObject.Properties|Where-Object Value -ne $true).Count -eq 0)
foreach($f in (Get-Content "$root/forms/test-results.json" -Raw|ConvertFrom-Json)){
    [xml]$x=Get-Content "$root/forms/$($f.case)/reports/TEST-com.example.generated.harness.GeneratedSectionFormsTest.xml" -Raw
    Check "$($f.case) four actual form tests" ([int]$x.testsuite.tests -eq 4 -and [int]$x.testsuite.failures -eq 0 -and [int]$x.testsuite.errors -eq 0 -and [int]$x.testsuite.skipped -eq 0)
    $manifest=Get-Content "$root/delivery/$($f.case)/manifest.json" -Raw|ConvertFrom-Json
    foreach($entry in $manifest.entries){Check "$($f.case) compiled download $($entry.relativePath)" ((Hash "$root/forms/$($f.case)/src/main/java/$($entry.relativePath)") -eq $entry.sha256)}
}
foreach($file in (Get-Content "$root/source-hashes.json" -Raw|ConvertFrom-Json)){
    Check "source snapshot $($file.path)" ((Hash "$root/sources/$($file.path)") -eq $file.sha256)
}
$negative=@(Get-Content "$root/negative-results.json" -Raw|ConvertFrom-Json)
Check 'five actual negative probes' ($negative.Count -eq 5 -and @($negative|Where-Object passed -ne $true).Count -eq 0)
$checks|ConvertTo-Json -Depth 5|Set-Content "$root/archive-verification.json" -Encoding utf8
Write-Output "Archive verified: $($checks.Count) checks; actual C $($summary.cTests), applied runtime $($summary.appliedRuntimeTests)."
