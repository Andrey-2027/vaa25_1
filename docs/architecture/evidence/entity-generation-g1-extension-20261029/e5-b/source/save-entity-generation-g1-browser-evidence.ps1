[CmdletBinding()]param([string]$Evidence='docs/architecture/evidence/entity-generation-g1-extension-20261029/e5-b')
$ErrorActionPreference='Stop'
$repo=Split-Path $PSScriptRoot -Parent;$dir=Join-Path $repo $Evidence
$base=Join-Path $repo 'outputs/entity-generation-g1-extension/e5-h/e5h-20261031'
foreach($artifact in (Get-Content "$base/build-complete.json" -Raw|ConvertFrom-Json)){
    if((Get-FileHash $artifact.path).Hash.ToLowerInvariant() -ne $artifact.sha256){throw 'E5.H artifact drift'}
}
Copy-Item "$base/build-complete.json" "$dir/e5h-artifact-bindings.json" -Force
New-Item -ItemType Directory "$dir/source" -Force|Out-Null
Copy-Item "$PSScriptRoot/entity-generation-g1-browser" "$dir/source" -Recurse -Force
foreach($name in @('run-entity-generation-g1-browser.ps1','verify-entity-generation-g1-browser-downloads.ps1','save-entity-generation-g1-browser-evidence.ps1')){Copy-Item "$PSScriptRoot/$name" "$dir/source" -Force}
$bindings=@()
foreach($run in @('e5b-20261031','e5b-standard-20261031','e5b-standard-final-20261031')){
    $out=Join-Path $repo "outputs/entity-generation-g1-extension/e5-b/$run";$dest="$dir/stand/$run"
    New-Item -ItemType Directory $dest -Force|Out-Null
    Get-ChildItem $out -File|Copy-Item -Destination $dest -Force
    foreach($jar in ((Get-Content "$out/classpath.txt" -Raw).Trim() -split ';')){
        if(!(Test-Path $jar)){throw "Missing consumed dependency: $jar"}
        $bindings+=@{run=$run;path=$jar;sha256=(Get-FileHash $jar).Hash.ToLowerInvariant()}
    }
    foreach($item in (Get-Content "$out/generated-bindings.json" -Raw|ConvertFrom-Json)){
        if((Get-FileHash "$out/project/src/main/java/$($item.relativePath)").Hash.ToLowerInvariant() -ne $item.sha256){throw 'Generated consumer drift'}
    }
    $adapterSource="$base/ui/build-app/classes/org/ip/views/admin";$adapterDest="$out/adapter-classes/org/ip/views/admin"
    foreach($file in (Get-ChildItem $adapterSource -File -Recurse)){
        $rel=[IO.Path]::GetRelativePath($adapterSource,$file.FullName)
        if((Get-FileHash $file.FullName).Hash -ne (Get-FileHash (Join-Path $adapterDest $rel)).Hash){throw 'P8 class drift'}
    }
}
$bindings|ConvertTo-Json -Depth 5|Set-Content "$dir/consumed-dependencies.json" -Encoding utf8
& "$PSScriptRoot/verify-entity-generation-g1-browser-downloads.ps1" -Evidence $Evidence
if($LASTEXITCODE){throw 'Download check failed'}
$inventory=@(Get-ChildItem $dir -File -Recurse|Where-Object Name -ne 'evidence-files.json'|ForEach-Object{@{relativePath=[IO.Path]::GetRelativePath($dir,$_.FullName).Replace('\','/');bytes=$_.Length;sha256=(Get-FileHash $_.FullName).Hash.ToLowerInvariant()}})
$inventory|ConvertTo-Json -Depth 4|Set-Content "$dir/evidence-files.json" -Encoding utf8
Write-Output "PASS: E5.H artifacts / generated Java / P8 classes unchanged; archived $($inventory.Count) files."
