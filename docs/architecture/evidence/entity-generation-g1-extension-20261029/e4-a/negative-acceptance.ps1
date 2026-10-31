$ErrorActionPreference='Stop'
$repo=(Get-Location).Path;$work=Join-Path $repo 'outputs/entity-generation-g1-extension/e4-a/work'
$inputRoot=Join-Path $repo 'outputs/entity-generation-g1-extension/e4-a/e4a-r2/input/document-2'
$manifest=Get-Content "$inputRoot/manifest.json" -Raw|ConvertFrom-Json
foreach($probe in @('missing','changed')){
    $dir="$work/negative-source-$probe";New-Item -ItemType Directory $dir -Force|Out-Null
    foreach($entry in $manifest.entries){
        if($probe -eq 'missing' -and $entry -eq $manifest.entries[2]){continue}
        $target=Join-Path $dir $entry.relativePath;New-Item -ItemType Directory (Split-Path $target -Parent) -Force|Out-Null
        Copy-Item (Join-Path $inputRoot $entry.relativePath) $target -Force
        if($probe -eq 'changed' -and $entry -eq $manifest.entries[2]){[IO.File]::AppendAllText($target,"`n// changed row`n")}
    }
    & ./scripts/test-entity-generation-g1.ps1 -SourceDirectory $dir -ManifestFile "$inputRoot/manifest.json" -SpecFile scripts/entity-generation-g1/scenarios/sections-document-2/ScenarioSpec.java -LocalRepository C:/Users/strim/.m2/repository -OutputDirectory "$work/c" -RunId "negative-$probe" *> "$work/c-negative-$probe.log"
    $summary=Get-Content "$work/c/negative-$probe/summary.json" -Raw|ConvertFrom-Json
    $reason=if($probe -eq 'missing'){'manifest-source-missing'}else{'manifest-mismatch'}
    if($summary.status -ne 'failure' -or $summary.failureReason -ne $reason -or $summary.maven.attempted){throw "C probe failed: $probe / $($summary.status) / $($summary.failureReason)"}
}
$cp=(Get-Content "$work/d-classpath.txt" -Raw).Trim();$java='C:/Program Files/Axiom/AxiomJDK-21/bin/java.exe'
$summary="$work/c/document-2-r2/summary.json";$plan="$inputRoot/reviewed-plan.json";$deps="$work/document-2-dependencies.json"
function ApplyProbe($project,$c,$d,$result,$status,$code){
    & $java -cp $cp org.ipro.design.apply.ApplyHarness --apply $project src/main/java $c $plan $d $result
    $value=Get-Content $result -Raw|ConvertFrom-Json
    if($value.status -ne $status -or $value.code -ne $code){throw "D probe failed: $($value.status)/$($value.code)"}
}
$foreign="$work/foreign-row-project";New-Item -ItemType Directory "$foreign/src/main/java" -Force|Out-Null
$row=Join-Path "$foreign/src/main/java" $manifest.entries[2].relativePath;New-Item -ItemType Directory (Split-Path $row -Parent) -Force|Out-Null
[IO.File]::WriteAllText($row,'// foreign row');$before=(Get-FileHash $row).Hash
ApplyProbe $foreign $summary $deps "$work/foreign-row.json" CONFLICT CONFLICT_UNOWNED
if((Get-FileHash $row).Hash -ne $before -or (Test-Path (Join-Path "$foreign/src/main/java" $manifest.entries[0].relativePath))){throw 'Foreign row altered or partial set published'}
$wrong=Get-Content $summary -Raw|ConvertFrom-Json;$wrong.tests.suites[0].name='com.example.generated.harness.MultiFileScenarioTest'
[IO.File]::WriteAllText("$work/wrong-suite-summary.json",($wrong|ConvertTo-Json -Depth 70))
ApplyProbe $foreign "$work/wrong-suite-summary.json" $deps "$work/wrong-suite.json" REJECTED C_TESTS_INCOMPLETE
$locations=Get-Content $deps -Raw|ConvertFrom-Json;$dep=$locations|Where-Object id -Like '*g1-reference-target*'
Copy-Item $dep.path "$work/changed-target.jar";$dep.path="$work/changed-target.jar"
$stream=[IO.File]::OpenWrite($dep.path);$stream.Position=$stream.Length;$stream.WriteByte(1);$stream.Dispose()
[IO.File]::WriteAllText("$work/changed-dependencies.json",($locations|ConvertTo-Json))
ApplyProbe $foreign $summary "$work/changed-dependencies.json" "$work/dependency-drift.json" REJECTED DEPENDENCIES_CHANGED
Write-Output 'Actual missing/changed row, foreign row, required suite and dependency drift probes passed'
