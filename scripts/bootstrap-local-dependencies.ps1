[CmdletBinding()]
param(
    [string]$MavenCommand,
    [string]$JavaHome,
    [string]$LocalRepository,
    [switch]$Offline,
    [switch]$RequireEmptyLocalRepository,
    [switch]$ValidateOnly,
    [switch]$SkipApplication,
    [switch]$AllowSourceDrift,
    [switch]$AllowToolchainDrift
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $PSCommandPath
$projectDirectory = Split-Path -Parent $scriptDirectory
$manifestPath = Join-Path $scriptDirectory 'local-dependencies.json'
$manifest = Get-Content -Raw -Encoding UTF8 -LiteralPath $manifestPath | ConvertFrom-Json

function Resolve-IntellijMavenCommand {
    param([string]$RequestedCommand)

    if ($RequestedCommand) {
        $resolved = [System.IO.Path]::GetFullPath($RequestedCommand)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw "Maven executable not found: $resolved"
        }
        return $resolved
    }

    if ($env:IDEA_MAVEN_HOME) {
        $fromEnvironment = Join-Path $env:IDEA_MAVEN_HOME 'bin\mvn.cmd'
        if (Test-Path -LiteralPath $fromEnvironment -PathType Leaf) {
            return [System.IO.Path]::GetFullPath($fromEnvironment)
        }
    }

    $jetBrainsDirectory = Join-Path $env:ProgramFiles 'JetBrains'
    if (Test-Path -LiteralPath $jetBrainsDirectory -PathType Container) {
        $candidates = Get-ChildItem -LiteralPath $jetBrainsDirectory -Directory -Filter 'IntelliJ IDEA*' |
            ForEach-Object {
                Get-Item -LiteralPath (Join-Path $_.FullName 'plugins\maven\lib\maven3\bin\mvn.cmd') `
                    -ErrorAction SilentlyContinue
            } |
            Sort-Object LastWriteTime -Descending
        if ($candidates) {
            return $candidates[0].FullName
        }
    }

    throw 'IntelliJ IDEA bundled Maven was not found. Pass -MavenCommand explicitly or set IDEA_MAVEN_HOME.'
}

function Get-JavaVersionOutput {
    param([string]$JavaExecutable)

    # java -version writes its normal version banner to stderr. Windows
    # PowerShell can promote that redirected stderr to NativeCommandError when
    # the script-wide ErrorActionPreference is Stop, so relax it only for this
    # diagnostic command and keep the combined output for version matching.
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        return (& $JavaExecutable -version 2>&1 | Out-String)
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
}

function Get-RelativePath {
    param(
        [string]$BasePath,
        [string]$TargetPath
    )

    $baseFullPath = [System.IO.Path]::GetFullPath($BasePath).TrimEnd('\', '/') + `
            [System.IO.Path]::DirectorySeparatorChar
    $targetFullPath = [System.IO.Path]::GetFullPath($TargetPath)
    $baseUri = [System.Uri]::new($baseFullPath)
    $targetUri = [System.Uri]::new($targetFullPath)
    return [System.Uri]::UnescapeDataString($baseUri.MakeRelativeUri($targetUri).ToString())
}

function Resolve-JavaHome {
    param(
        [string]$RequestedJavaHome,
        [int]$RequiredMajor
    )

    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($RequestedJavaHome) {
        $candidates.Add($RequestedJavaHome)
    }
    if ($env:JAVA_HOME) {
        $candidates.Add($env:JAVA_HOME)
    }

    $axiomDirectory = Join-Path $env:ProgramFiles 'Axiom'
    if (Test-Path -LiteralPath $axiomDirectory -PathType Container) {
        Get-ChildItem -LiteralPath $axiomDirectory -Directory -Filter '*JDK-21*' |
            Sort-Object LastWriteTime -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    $javaDirectory = Join-Path $env:ProgramFiles 'Java'
    if (Test-Path -LiteralPath $javaDirectory -PathType Container) {
        Get-ChildItem -LiteralPath $javaDirectory -Directory -Filter 'jdk-21*' |
            Sort-Object LastWriteTime -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        $resolved = [System.IO.Path]::GetFullPath($candidate)
        $javaExecutable = Join-Path $resolved 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $javaExecutable -PathType Leaf)) {
            continue
        }
        $candidateVersion = Get-JavaVersionOutput $javaExecutable
        if ($candidateVersion -match "version `"$RequiredMajor\.") {
            return $resolved
        }
    }

    throw "JDK $RequiredMajor was not found. Pass its installation directory with -JavaHome."
}

function Get-SourceTreeFingerprint {
    param(
        [string]$BasePath,
        [object[]]$Inputs
    )

    $allFiles = @()
    foreach ($inputPath in $Inputs) {
        $resolvedInput = Join-Path $BasePath ([string]$inputPath)
        if (Test-Path -LiteralPath $resolvedInput -PathType Leaf) {
            $allFiles += Get-Item -LiteralPath $resolvedInput
        } elseif (Test-Path -LiteralPath $resolvedInput -PathType Container) {
            $allFiles += Get-ChildItem -LiteralPath $resolvedInput -Recurse -File
        } else {
            throw "Fingerprint input not found: $resolvedInput"
        }
    }

    $files = $allFiles |
        Where-Object {
            $_.FullName -notmatch '[\\/](target|node_modules|\.git|\.idea|\.freebuff)[\\/]'
        } |
        Sort-Object FullName -Unique

    $content = [System.Text.StringBuilder]::new()
    foreach ($file in $files) {
        $relativePath = (Get-RelativePath $BasePath $file.FullName).Replace('\', '/')
        $fileHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
        [void]$content.Append($relativePath).Append("`0").Append($fileHash).Append("`n")
    }

    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($content.ToString())
        $digest = [System.BitConverter]::ToString($algorithm.ComputeHash($bytes)).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }

    return [pscustomobject]@{
        FileCount = $files.Count
        Sha256 = $digest
    }
}

function Invoke-CheckedMaven {
    param(
        [string]$Label,
        [string]$PomPath,
        [object[]]$Arguments
    )

    Write-Host "`n==> $Label"
    $mavenArguments = [System.Collections.Generic.List[string]]::new()
    $mavenArguments.Add('--batch-mode')
    $mavenArguments.Add('--no-transfer-progress')
    if ($Offline) {
        $mavenArguments.Add('--offline')
    }
    if ($resolvedLocalRepository) {
        $mavenArguments.Add("-Dmaven.repo.local=$resolvedLocalRepository")
    }
    $mavenArguments.Add('-f')
    $mavenArguments.Add($PomPath)
    foreach ($argument in $Arguments) {
        $mavenArguments.Add([string]$argument)
    }

    & $resolvedMavenCommand @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven failed for '$Label' with exit code $LASTEXITCODE."
    }
}

if ($manifest.schemaVersion -ne 1) {
    throw "Unsupported manifest schema: $($manifest.schemaVersion)"
}

$resolvedMavenCommand = Resolve-IntellijMavenCommand $MavenCommand
$expectedJavaMajor = [int]$manifest.toolchain.javaMajor
$resolvedJavaHome = Resolve-JavaHome $JavaHome $expectedJavaMajor
$env:JAVA_HOME = $resolvedJavaHome

$javaVersionOutput = Get-JavaVersionOutput (Join-Path $resolvedJavaHome 'bin\java.exe')
if ($javaVersionOutput -notmatch "version `"$expectedJavaMajor\.") {
    throw "JDK $expectedJavaMajor is required. Detected: $javaVersionOutput"
}

$mavenVersionOutput = (& $resolvedMavenCommand -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0) {
    throw "Unable to execute Maven: $resolvedMavenCommand"
}
$expectedMavenVersion = [string]$manifest.toolchain.testedMavenVersion
if (-not $AllowToolchainDrift -and $mavenVersionOutput -notmatch "Apache Maven $([regex]::Escape($expectedMavenVersion))") {
    throw "Maven $expectedMavenVersion is required by the manifest. Use -AllowToolchainDrift only for an intentional toolchain test."
}

$resolvedLocalRepository = $null
if ($LocalRepository) {
    $resolvedLocalRepository = [System.IO.Path]::GetFullPath((Join-Path $projectDirectory $LocalRepository))
    $applicationTarget = [System.IO.Path]::GetFullPath((Join-Path $projectDirectory 'target'))
    $targetPrefix = $applicationTarget.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if ($resolvedLocalRepository -eq $applicationTarget -or $resolvedLocalRepository.StartsWith(
            $targetPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Local repository must not be inside '$applicationTarget': the application clean phase would delete Maven's running plugins."
    }
    if (Test-Path -LiteralPath $resolvedLocalRepository -PathType Container) {
        if ($RequireEmptyLocalRepository -and (Get-ChildItem -LiteralPath $resolvedLocalRepository -Force | Select-Object -First 1)) {
            throw "Local repository must be empty: $resolvedLocalRepository"
        }
    } else {
        New-Item -ItemType Directory -Path $resolvedLocalRepository | Out-Null
    }
}

Write-Host "Maven: $resolvedMavenCommand"
Write-Host "Java:  $resolvedJavaHome"
if ($resolvedLocalRepository) {
    Write-Host "Repo:  $resolvedLocalRepository"
}

$resolvedProjects = @{}
foreach ($project in $manifest.projects) {
    $basePath = [System.IO.Path]::GetFullPath((Join-Path $projectDirectory ([string]$project.relativePath)))
    if (-not (Test-Path -LiteralPath $basePath -PathType Container)) {
        throw "Required sibling project '$($project.id)' not found: $basePath"
    }
    $resolvedProjects[[string]$project.id] = $basePath

    $actualFingerprint = Get-SourceTreeFingerprint $basePath $project.fingerprint.inputs
    $fingerprintMatches = $actualFingerprint.Sha256 -eq [string]$project.fingerprint.sha256 -and
        $actualFingerprint.FileCount -eq [int]$project.fingerprint.fileCount
    if (-not $fingerprintMatches) {
        $message = "Source drift for '$($project.id)': expected $($project.fingerprint.sha256) ($($project.fingerprint.fileCount) files), got $($actualFingerprint.Sha256) ($($actualFingerprint.FileCount) files)."
        if ($AllowSourceDrift) {
            Write-Warning $message
        } else {
            throw "$message Review the changes and update the manifest intentionally, or use -AllowSourceDrift for a diagnostic build."
        }
    }
    Write-Host "OK fingerprint: $($project.id)"
}

if ($ValidateOnly) {
    Write-Host 'Manifest, workspace layout, source fingerprints and toolchain are valid.'
    exit 0
}

foreach ($project in $manifest.projects) {
    $basePath = [string]$resolvedProjects[[string]$project.id]
    foreach ($step in $project.buildSteps) {
        $pomPath = Join-Path $basePath ([string]$step.pom)
        Invoke-CheckedMaven "$($project.id):$($step.pom)" $pomPath $step.arguments
    }
}

if (-not $SkipApplication) {
    $applicationPom = Join-Path $projectDirectory ([string]$manifest.application.pom)
    Invoke-CheckedMaven 'GitVaa25' $applicationPom $manifest.application.arguments
}

Write-Host "`nLocal dependency bootstrap completed successfully."
