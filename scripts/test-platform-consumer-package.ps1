[CmdletBinding()]
param([Parameter(Mandatory)][string]$Jar,
      [Parameter(Mandatory)][string]$LocalRepository,
      [Parameter(Mandatory)][string]$OutputDirectory)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Resolve-Path $Jar))
$libraries = @(); $inventory = @()
try {
    foreach ($entry in $archive.Entries) {
        if ($entry.FullName -like 'BOOT-INF/classes/*') {
            $inventory += $entry.FullName
            if ($entry.FullName -like '*.class' -and $entry.FullName -notlike 'BOOT-INF/classes/com/example/materialrequests/*') {
                throw "Unexpected application class: $($entry.FullName)"
            }
        }
        if ($entry.FullName -notlike 'BOOT-INF/lib/*.jar') { continue }
        $memory = [IO.MemoryStream]::new()
        $stream = $entry.Open()
        try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
        try {
            $bytes = $memory.ToArray()
            $hash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
            $memory.Position = 0
            $nested = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
            try {
                foreach ($nestedEntry in $nested.Entries) {
                    if ($nestedEntry.FullName -like 'org/ip/*') { throw "Original application entry in $($entry.Name): $($nestedEntry.FullName)" }
                }
            } finally { $nested.Dispose() }
            if ($entry.Name -match '^(platform-.+)-1\.0-SNAPSHOT\.jar$') {
                $artifact = $matches[1]
                $repositoryJar = Join-Path $LocalRepository "org/ipro/$artifact/1.0-SNAPSHOT/$artifact-1.0-SNAPSHOT.jar"
                if ((Get-FileHash $repositoryJar -Algorithm SHA256).Hash -ne $hash) { throw "Packaged artifact differs: $artifact" }
                $libraries += [ordered]@{ artifact=$artifact; sha256=$hash }
            }
        } finally { $memory.Dispose() }
    }
    if ($libraries.Count -lt 1) { throw 'No packaged platform artifacts' }
    if ($inventory -notcontains 'BOOT-INF/classes/application.properties' -or
        -not ($archive.Entries.FullName -like 'META-INF/VAADIN/webapp/VAADIN/build/*.js')) {
        throw 'Missing own application config or production frontend'
    }
    [ordered]@{ status='passed'; jarSha256=(Get-FileHash $Jar).Hash;
        platformLibraries=$libraries; applicationEntries=$inventory } |
        ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutputDirectory 'package-provenance.json') -Encoding utf8
    Write-Host "Package provenance passed: $($libraries.Count) platform JARs match repository."
} finally { $archive.Dispose() }
