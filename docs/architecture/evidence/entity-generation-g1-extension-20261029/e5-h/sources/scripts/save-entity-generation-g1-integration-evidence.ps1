[CmdletBinding()]
param([string]$RunId='e5h-20261031',
      [string]$EvidenceDirectory='docs/architecture/evidence/entity-generation-g1-extension-20261029/e5-h')
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent
$run=Join-Path $repo "outputs/entity-generation-g1-extension/e5-h/$RunId"
$dest=[IO.Path]::GetFullPath((Join-Path $repo $EvidenceDirectory))
$summary=Get-Content "$run/summary.json" -Raw|ConvertFrom-Json
if($summary.status -ne 'passed'){throw 'E5.H has not passed verification'}
New-Item -ItemType Directory -Path $dest -Force|Out-Null
function CopyFile($source,$relative){
    $target=Join-Path $dest $relative
    New-Item -ItemType Directory -Path (Split-Path $target -Parent) -Force|Out-Null
    Copy-Item -LiteralPath $source -Destination $target -Force
}
function CopyTree($source,$relative){
    foreach($file in Get-ChildItem -LiteralPath $source -Recurse -File -Force){CopyFile $file.FullName (Join-Path $relative ([IO.Path]::GetRelativePath($source,$file.FullName)))}
}
foreach($name in @('summary.json','verification.json','actual-results.json','negative-results.json',
    'build-complete.json','source-fingerprints.json','source-fingerprints-final.json',
    'local-dependencies.before.json','settings.xml')){CopyFile "$run/$name" $name}
CopyFile "$repo/scripts/local-dependencies.json" 'local-dependencies.after.json'
CopyTree "$run/inputs" 'inputs'
CopyTree "$run/delivery" 'delivery'
foreach($group in @('backend','ui','api')){
    CopyFile "$run/$group/test-results.json" "$group/test-results.json"
    CopyFile "$run/$group/tests.log" "$group/tests.log"
    foreach($file in Get-ChildItem "$run/$group" -Recurse -Filter 'TEST-*.xml'){
        # API is recorded separately; UI totals refer to the component regression list.
        if($group -eq 'ui' -and $file.Name -eq 'TEST-org.ipro.PlatformApiBaselineTest.xml'){continue}
        CopyFile $file.FullName "$group/reports/$($file.Name)"
    }
    foreach($file in Get-ChildItem "$run/$group" -File -Filter '*.prior-*'){CopyFile $file.FullName "prior/$group/$($file.Name)"}
}
CopyFile "$run/build/build.log" 'build/build.log'
foreach($file in Get-ChildItem "$run/build" -File -Filter '*.prior-*'){CopyFile $file.FullName "prior/build/$($file.Name)"}
if(Test-Path "$run/prior"){CopyTree "$run/prior" 'prior'}
CopyFile "$run/d/selftest/selftest-summary.json" 'd/selftest-summary.json'
CopyFile "$run/d/selftest/selftest.log" 'd/selftest.log'
CopyTree "$run/d/selftest/work/three-file-evidence" 'd/proofs'
foreach($file in Get-ChildItem "$run/d/selftest/work/three-file-evidence" -Filter '*.json'){
    $proof=Get-Content $file.FullName -Raw|ConvertFrom-Json
    CopyTree $proof.projectRoot "d/projects/$(Split-Path $proof.projectRoot -Leaf)"
}
$cself=Get-ChildItem "$run/c-selftest" -Recurse -Filter selftest-summary.json
CopyFile $cself.FullName 'c/selftest-summary.json'
foreach($file in Get-ChildItem $cself.Directory.FullName -Recurse -File|Where-Object{$_.Name -eq 'summary.json' -or $_.Name -like 'TEST-*.xml'}){
    CopyFile $file.FullName (Join-Path 'c/selftest' ([IO.Path]::GetRelativePath($cself.Directory.FullName,$file.FullName)))
}
foreach($case in (Get-Content "$run/actual-results.json" -Raw|ConvertFrom-Json)){
    $root="$run/actual/$($case.id)";$rel="actual/$($case.id)"
    foreach($name in @('result.json','applied.json','noop.json','conflict.json','dependencies.json','dependencies.log','applied-runtime.log','c-launcher.log')){CopyFile "$root/$name" "$rel/$name"}
    $applied=Get-Content "$root/applied.json" -Raw|ConvertFrom-Json
    CopyFile $applied.receipt "$rel/apply-receipt.json"
    foreach($file in Get-ChildItem "$root/c/accepted" -File){CopyFile $file.FullName "$rel/c/$($file.Name)"}
    CopyTree "$root/c/accepted/input" "$rel/c/input"
    CopyTree "$root/c/accepted/reports" "$rel/c/reports"
    CopyFile "$root/project/pom.xml" "$rel/project/pom.xml"
    CopyTree "$root/project/src" "$rel/project/src"
    CopyTree "$root/project/target/surefire-reports" "$rel/runtime-reports"
    CopyFile "$root/project/target/consumer-runtime-dependency-tree.json" "$rel/runtime-dependency-tree.json"
    CopyFile "$root/project/target/consumer-test-classpath.txt" "$rel/test-classpath.txt"
}
CopyFile "$run/forms/test-results.json" 'forms/test-results.json'
CopyFile "$run/forms/delivery-bindings.json" 'forms/delivery-bindings.json'
CopyFile "$run/forms/tests.log" 'forms/tests.log'
foreach($case in (Get-Content "$run/forms/test-results.json" -Raw|ConvertFrom-Json)){
    CopyTree "$run/forms/$($case.case)/src" "forms/$($case.case)/src"
    CopyTree "$run/forms/$($case.case)/target/surefire-reports" "forms/$($case.case)/reports"
    CopyFile "$run/forms/$($case.case)/target/consumer-runtime-dependency-tree.json" "forms/$($case.case)/runtime-dependency-tree.json"
    CopyFile "$run/forms/$($case.case)/target/consumer-test-classpath.txt" "forms/$($case.case)/test-classpath.txt"
}
foreach($file in Get-ChildItem "$run/negatives" -Recurse -File|Where-Object{$_.Extension -ne '.jar'}){
    CopyFile $file.FullName (Join-Path 'negatives' ([IO.Path]::GetRelativePath("$run/negatives",$file.FullName)))
}
CopyTree "$run/contracts" 'contracts'
if(Test-Path "$run/api/before"){CopyTree "$run/api/before" 'api/before'}
CopyTree "$repo/src/test/resources/platform-api-baseline" 'api/after'
$sourcePaths=@('scripts/test-entity-generation-g1-integration.ps1','scripts/save-entity-generation-g1-integration-evidence.ps1',
    'scripts/verify-entity-generation-g1-integration-evidence.ps1',
    'scripts/test-entity-generation-g1.ps1','scripts/apply-entity-generation-g1.ps1','scripts/test-entity-generation-g1-section-forms.ps1',
    'src/test/java/org/ip/views/admin/EntityDraftMatrixScenarioTest.java','src/test/java/org/ipro/PlatformApiBaselineTest.java',
    'src/test/java/org/ipro/PlatformCoreModuleTest.java','src/test/java/org/ipro/PlatformCoreBoundaryTest.java',
    'src/test/java/org/ipro/PlatformCoreSurfaceTest.java','src/test/java/org/ipro/PlatformVaadinSurfaceTest.java',
    'src/test/resources/platform-core-surface.txt','src/test/resources/platform-vaadin-surface.txt')
$sourcePaths+=@('src/main/java/org/ip/views/admin/EntityDraftEditorView.java','src/main/java/org/ip/views/admin/DraftEditorSession.java',
    'entity-design-core/pom.xml','entity-design-json/pom.xml','entity-design-runtime/pom.xml')
foreach($directory in @('scripts/entity-generation-g1','scripts/entity-generation-g1-matrix','entity-design-core/src','entity-design-json/src','entity-design-runtime/src')){
    $sourcePaths+=@(Get-ChildItem "$repo/$directory" -Recurse -File|ForEach-Object{[IO.Path]::GetRelativePath($repo,$_.FullName)})
}
$sourcePaths+=@(Get-ChildItem "$repo/scripts/entity-generation-g1-apply/src/main/java" -Recurse -File|ForEach-Object{[IO.Path]::GetRelativePath($repo,$_.FullName)})
$sourcePaths=@($sourcePaths|ForEach-Object{$_.Replace('\','/')}|Sort-Object -Unique)
$hashes=foreach($path in $sourcePaths){CopyFile "$repo/$path" "sources/$path";[ordered]@{path=$path;sha256=(Get-FileHash -LiteralPath "$repo/$path" -Algorithm SHA256).Hash.ToLowerInvariant()}}
$hashes|ConvertTo-Json -Depth 4|Set-Content "$dest/source-hashes.json" -Encoding utf8
$inventory=foreach($file in Get-ChildItem "$run/repository/org/ipro" -Recurse -File|Where-Object Extension -in '.jar','.pom'){
    [ordered]@{relativePath=[IO.Path]::GetRelativePath("$run/repository",$file.FullName).Replace('\','/');sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
}
$inventory|ConvertTo-Json -Depth 4|Set-Content "$dest/repository-inventory.json" -Encoding utf8
Write-Output "E5.H evidence saved: $dest"
