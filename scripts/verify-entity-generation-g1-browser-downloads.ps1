[CmdletBinding()]param([string]$Evidence='docs/architecture/evidence/entity-generation-g1-extension-20261029/e5-b', [string]$Baseline='outputs/entity-generation-g1-extension/e5-h/e5h-20261031')
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repo=Split-Path $PSScriptRoot -Parent
$dir=Join-Path $repo $Evidence
$results=@()
foreach($case in @(@('DocumentSections2','sections-document-2'),@('OwnedCatalogSections2','sections-owned-catalog-2'))){
    $zip=[IO.Compression.ZipFile]::OpenRead("$dir/browser/downloads/$($case[0]).zip")
    try {
        if($zip.Entries.Count -ne 4){throw 'Expected manifest plus three Java sources'}
        $r=[IO.StreamReader]::new($zip.GetEntry('generation-manifest.json').Open());$manifest=$r.ReadToEnd()|ConvertFrom-Json;$r.Dispose()
        if($manifest.entries.Count -ne 3){throw 'Incomplete manifest'}
        foreach($entry in $manifest.entries){
            $stream=$zip.GetEntry($entry.relativePath).Open();$buffer=[IO.MemoryStream]::new();$stream.CopyTo($buffer);$stream.Dispose();$bytes=$buffer.ToArray();$buffer.Dispose()
            $sha=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
            $baselineFile=Join-Path $repo "$Baseline/actual/$($case[1])/project/src/main/java/$($entry.relativePath)"
            if($sha -ne $entry.sha256 -or $bytes.Length -ne $entry.bytes -or $sha -ne (Get-FileHash $baselineFile).Hash.ToLowerInvariant()){throw "Byte mismatch: $($entry.relativePath)"}
            $text=[Text.UTF8Encoding]::new($false,$true).GetString($bytes)
            if($text.Contains("`r") -or ($bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191)){throw 'Expected UTF-8 no BOM / LF'}
            $results+=@{bundle=$case[0];relativePath=$entry.relativePath;sha256=$sha;bytes=$bytes.Length;baselineMatch=$true}
        }
    } finally {$zip.Dispose()}
}
$download="$dir/browser/downloads/BrowserGeneratedDocument.java"
if((Get-FileHash $download).Hash -ne (Get-FileHash "$dir/browser/downloads/BrowserGeneratedDocument.preview.java").Hash){throw 'Single Java differs from browser preview'}
$results+=@{bundle='manual-document';previewDownloadMatch=$true;sha256=(Get-FileHash $download).Hash.ToLowerInvariant()}
$results|ConvertTo-Json -Depth 6|Set-Content "$dir/download-verification.json" -Encoding utf8
Write-Output 'PASS: 6 ZIP Java entries match E5.H bytes, manifest sizes/hashes, UTF-8/LF; single Java matches preview.'
