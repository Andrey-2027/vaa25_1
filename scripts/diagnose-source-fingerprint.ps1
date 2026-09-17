<#
    Diagnose a manifest fingerprint mismatch.

    Why a separate tool. The bootstrap script only says "review the changes and update the manifest
    intentionally" when a fingerprint drifts. It does not answer the question that matters: did the
    content really change, or is the algorithm itself unsound? For projects recorded with algorithm
    v1 (culture-aware Sort-Object) the second case is real: the same tree yields a different SHA
    depending on the process culture, so a reported "drift" may be a measurement artifact rather
    than an edit. The difference decides who has to act: review someone else's changes, or re-record
    the value with the ordinal algorithm and leave the other repository alone.

    What it does: recomputes the project fingerprint with algorithm v2 (ordinal) and with algorithm
    v1 under several cultures, and shows which combination reproduces the recorded value.

    Example:
        powershell -NoProfile -File scripts/diagnose-source-fingerprint.ps1 -ProjectId dynamicreports

    Note: this file is intentionally ASCII-only. Windows PowerShell 5.1 reads .ps1 sources using the
    system ANSI code page unless they carry a BOM, so non-ASCII text in a script breaks the parser.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ProjectId,
    [string[]]$Cultures = @('InvariantCulture', 'en-US', 'de-DE', 'sv-SE', 'tr-TR')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $PSCommandPath
$projectDirectory = Split-Path -Parent $scriptDirectory
$manifestPath = Join-Path $scriptDirectory 'local-dependencies.json'
$manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json

$project = $manifest.projects | Where-Object { $_.id -eq $ProjectId }
if (-not $project) {
    throw "Project '$ProjectId' is not defined by the manifest."
}
if (-not $project.fingerprint) {
    throw "Project '$ProjectId' has no recorded fingerprint."
}

$basePath = [System.IO.Path]::GetFullPath((Join-Path $projectDirectory ([string]$project.relativePath)))
if (-not (Test-Path -LiteralPath $basePath -PathType Container)) {
    throw "Project path not found: $basePath"
}

# Same helper as the bootstrap script: the comparison is only meaningful when the algorithm matches
# exactly, otherwise the diagnostic becomes a source of drift itself.
function Get-RelativePath {
    param(
        [string]$BasePath,
        [string]$TargetPath
    )

    $baseFullPath = [System.IO.Path]::GetFullPath($BasePath).TrimEnd('\', '/') +
            [System.IO.Path]::DirectorySeparatorChar
    $targetFullPath = [System.IO.Path]::GetFullPath($TargetPath)
    $baseUri = [System.Uri]::new($baseFullPath)
    $targetUri = [System.Uri]::new($targetFullPath)
    return [System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($targetUri).ToString())
}

function Get-Digest {
    param(
        [string[]]$RelativePaths,
        [string[]]$FullNames
    )

    $content = [System.Text.StringBuilder]::new()
    $fileCount = 0
    $previousPath = $null
    for ($index = 0; $index -lt $RelativePaths.Count; $index++) {
        $relativePath = $RelativePaths[$index]
        if ($relativePath -eq $previousPath) {
            continue
        }
        $previousPath = $relativePath
        $fileHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $FullNames[$index]).Hash.ToLowerInvariant()
        [void]$content.Append($relativePath).Append("`0").Append($fileHash).Append("`n")
        $fileCount++
    }

    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($content.ToString())
        $digest = [System.BitConverter]::ToString($hasher.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $hasher.Dispose()
    }

    return [pscustomobject]@{ Sha256 = $digest; FileCount = $fileCount }
}

$allFiles = @()
foreach ($inputPath in $project.fingerprint.inputs) {
    $resolvedInput = Join-Path $basePath ([string]$inputPath)
    if (Test-Path -LiteralPath $resolvedInput -PathType Leaf) {
        $allFiles += Get-Item -LiteralPath $resolvedInput
    } elseif (Test-Path -LiteralPath $resolvedInput -PathType Container) {
        $allFiles += Get-ChildItem -LiteralPath $resolvedInput -Recurse -File
    } else {
        throw "Fingerprint input not found: $resolvedInput"
    }
}

$relativePaths = [System.Collections.Generic.List[string]]::new()
$fullNames = [System.Collections.Generic.List[string]]::new()
foreach ($file in ($allFiles | Where-Object {
            $_.FullName -notmatch '[\\/](target|node_modules|\.git|\.idea|\.freebuff)[\\/]' })) {
    $relativePaths.Add((Get-RelativePath $basePath $file.FullName).Replace('\', '/'))
    $fullNames.Add($file.FullName)
}

$recorded = [string]$project.fingerprint.sha256
$recordedCount = [int]$project.fingerprint.fileCount
$recordedAlgorithm = [string]$project.fingerprint.algorithm

Write-Host "Project:  $ProjectId ($basePath)"
Write-Host "Recorded: $recorded ($recordedCount files, $recordedAlgorithm)"
Write-Host "Files:    $($relativePaths.Count) collected from inputs $($project.fingerprint.inputs -join ', ')"
Write-Host ''

$results = [System.Collections.Generic.List[object]]::new()

$ordinalPaths = $relativePaths.ToArray()
$ordinalNames = $fullNames.ToArray()
[System.Array]::Sort($ordinalPaths, $ordinalNames, [System.StringComparer]::Ordinal)
$results.Add([pscustomobject]@{
        Label = 'v2 (ordinal)'
        Digest = (Get-Digest $ordinalPaths $ordinalNames)
    })

foreach ($cultureName in $Cultures) {
    if ($cultureName -eq 'InvariantCulture') {
        $culture = [System.Globalization.CultureInfo]::InvariantCulture
    } else {
        $culture = [System.Globalization.CultureInfo]::GetCultureInfo($cultureName)
    }
    [System.Threading.Thread]::CurrentThread.CurrentCulture = $culture

    $pairs = for ($index = 0; $index -lt $relativePaths.Count; $index++) {
        [pscustomobject]@{ Path = $relativePaths[$index]; Name = $fullNames[$index] }
    }
    $sorted = @($pairs | Sort-Object -Property Path)
    $sortedPaths = [string[]]@($sorted | ForEach-Object { $_.Path })
    $sortedNames = [string[]]@($sorted | ForEach-Object { $_.Name })

    $results.Add([pscustomobject]@{
            Label = "v1 ($cultureName)"
            Digest = (Get-Digest $sortedPaths $sortedNames)
        })
}

foreach ($result in $results) {
    $marker = ''
    if ($result.Digest.Sha256 -eq $recorded) {
        $marker = '   <-- reproduces the recorded value'
    }
    Write-Host ("{0,-18} {1} ({2} files){3}" -f $result.Label, $result.Digest.Sha256, $result.Digest.FileCount, $marker)
}

Write-Host ''
$reproducing = @($results | Where-Object { $_.Digest.Sha256 -eq $recorded })
$ordinal = $results[0]
$cultureDependent = @($results | Where-Object { $_.Label -like 'v1*' -and $_.Digest.Sha256 -eq $recorded })
$distinctHashes = @($results | ForEach-Object { $_.Digest.Sha256 } | Sort-Object -Unique)

if ($reproducing.Count -eq 0) {
    Write-Host 'VERDICT: the content really did change.'
    Write-Host 'No combination of algorithm and culture reproduces the recorded value, so this is an'
    Write-Host 'edit of the files, not a measurement artifact. It has to be reviewed and re-recorded'
    Write-Host 'deliberately; there is no automatic flag for it in the bootstrap script.'
} elseif ($cultureDependent.Count -gt 0) {
    Write-Host 'VERDICT: no content drift - the algorithm is unsound.'
    Write-Host "The recorded value is reproduced by the culture-aware v1 sort ($($cultureDependent.Label -join ', ')),"
    Write-Host 'and some culture makes the same tree produce a different SHA. The recorded value was'
    Write-Host 'therefore not reproducible: "matched" meant the checker happened to share the culture.'
    Write-Host "Ordinal value (v2): $($ordinal.Digest.Sha256) ($($ordinal.Digest.FileCount) files)."
    if ($distinctHashes.Count -gt 1) {
        Write-Host 'The cultures disagree with each other - that is the proof of unreproducibility.'
    } else {
        Write-Host 'The probed cultures agree with each other, yet the recorded value still comes from v1:'
        Write-Host 'switching to v2 removes the dependence on the checker implementation.'
    }
} else {
    Write-Host 'VERDICT: the content did not change.'
    Write-Host "The recorded value matches algorithm '$recordedAlgorithm' on the current tree."
}
