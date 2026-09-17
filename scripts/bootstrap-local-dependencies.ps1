[CmdletBinding()]
param(
    [string]$MavenCommand,
    [string]$JavaHome,
    [string]$LocalRepository,
    [switch]$Offline,
    [switch]$RequireEmptyLocalRepository,
    [switch]$FreshLocalRepository,
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
        [object[]]$Inputs,
        [string]$AlgorithmName
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

    $relativePaths = [System.Collections.Generic.List[string]]::new()
    $fullNames = [System.Collections.Generic.List[string]]::new()
    foreach ($file in ($allFiles | Where-Object {
            $_.FullName -notmatch '[\\/](target|node_modules|\.git|\.idea|\.freebuff)[\\/]' })) {
        $relativePaths.Add((Get-RelativePath $BasePath $file.FullName).Replace('\', '/'))
        $fullNames.Add($file.FullName)
    }

    $pathArray = $relativePaths.ToArray()
    $nameArray = $fullNames.ToArray()
    switch ($AlgorithmName) {
        # v2: порядок путей задан явно — ordinal. Прежняя сортировка (v1) была
        # culture-aware, поэтому её результат зависел от культуры процесса: «fingerprint
        # совпал» означало совпадение реализации проверяющего, а не файлов. Для
        # in-repo проектов разрешён только v2 (проверяется ниже); v1 остаётся для
        # внешних проектов, чьи записанные значения менять не наша задача.
        'gitvaa-source-tree-sha256-v2' {
            [System.Array]::Sort($pathArray, $nameArray, [System.StringComparer]::Ordinal)
        }
        'gitvaa-source-tree-sha256-v1' {
            $pairs = for ($index = 0; $index -lt $pathArray.Count; $index++) {
                [pscustomobject]@{ Path = $pathArray[$index]; Name = $nameArray[$index] }
            }
            $sorted = @($pairs | Sort-Object -Property Path)
            $pathArray = [string[]]@($sorted | ForEach-Object { $_.Path })
            $nameArray = [string[]]@($sorted | ForEach-Object { $_.Name })
        }
        default {
            throw "Unsupported fingerprint algorithm: $AlgorithmName"
        }
    }

    $content = [System.Text.StringBuilder]::new()
    $fileCount = 0
    $previousPath = $null
    for ($index = 0; $index -lt $pathArray.Count; $index++) {
        $relativePath = $pathArray[$index]
        if ($relativePath -eq $previousPath) {
            continue
        }
        $previousPath = $relativePath
        $fileHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $nameArray[$index]).Hash.ToLowerInvariant()
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

    return [pscustomobject]@{
        FileCount = $fileCount
        Sha256 = $digest
    }
}

<#
  Порядок сборки по манифесту.

  Раньше скрипт собирал проекты ровно в порядке JSON, а dependsOn был только документацией.
  С пустым локальным репозиторием это не воспроизводилось: platform-contracts зависит от
  crudui-core, а crudui стоял в манифесте позже — первый же build step падал на неразрешимой
  зависимости. Теперь порядок считается из dependsOn (Kahn), а неизвестный id или цикл роняют
  проверку до первой сборки: ошибка манифеста, а не Maven.
#>
function Expand-ReactorPoms {
    param([string[]]$PomPaths)

    $expanded = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $queue = [System.Collections.Generic.Queue[string]]::new()
    foreach ($pomPath in $PomPaths) {
        if ($expanded.Add($pomPath)) {
            $queue.Enqueue($pomPath)
        }
    }

    # Пом'ы модулей реактора: без них ребро «модуль -> артефакт workspace» осталось бы невидимым,
    # ровно как в WorkspaceManifestTest.expandReactors.
    while ($queue.Count -gt 0) {
        $pomPath = $queue.Dequeue()
        if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) {
            continue
        }
        $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $pomPath
        $directory = Split-Path -Parent $pomPath
        foreach ($module in [regex]::Matches($text, '<module>([^<]+)</module>')) {
            $modulePom = [System.IO.Path]::GetFullPath(
                (Join-Path $directory ($module.Groups[1].Value.Trim() + '/pom.xml')))
            if ($expanded.Add($modulePom)) {
                $queue.Enqueue($modulePom)
            }
        }
    }

    return $expanded
}

function Get-PomReferencedWorkspaceProjects {
    param(
        [string[]]$PomPaths,
        [hashtable]$WorkspaceArtifacts,
        [string]$OwnerId
    )

    $referenced = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($pomPath in (Expand-ReactorPoms $PomPaths)) {
        if (-not (Test-Path -LiteralPath $pomPath -PathType Leaf)) {
            continue
        }
        $text = Get-Content -Raw -Encoding UTF8 -LiteralPath $pomPath
        # dependencyManagement — это объявление версий, а не зависимость: BOM-родитель дал бы
        # рёбра, которых в сборке нет.
        $dependencies = [regex]::Replace($text, '<dependencyManagement>.*?</dependencyManagement>', ' ',
            [System.Text.RegularExpressions.RegexOptions]::Singleline)

        $blocks = [System.Collections.Generic.List[string]]::new()
        foreach ($match in [regex]::Matches($dependencies, '<dependency>(.*?)</dependency>',
                [System.Text.RegularExpressions.RegexOptions]::Singleline)) {
            $blocks.Add($match.Groups[1].Value)
        }
        foreach ($match in [regex]::Matches($text, '<parent>(.*?)</parent>',
                [System.Text.RegularExpressions.RegexOptions]::Singleline)) {
            $blocks.Add($match.Groups[1].Value)
        }

        foreach ($block in $blocks) {
            $coordinate = [regex]::Match($block, '<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>')
            if (-not $coordinate.Success) {
                continue
            }
            $key = $coordinate.Groups[1].Value.Trim() + ':' + $coordinate.Groups[2].Value.Trim()
            if ($WorkspaceArtifacts.ContainsKey($key)) {
                $owner = [string]$WorkspaceArtifacts[$key]
                if ($owner -ne $OwnerId) {
                    [void]$referenced.Add($owner)
                }
            }
        }
    }

    return $referenced
}

function Assert-PublishedCoordinatesAreUnique {
    param([object[]]$Projects)

    $artifacts = [System.Collections.Hashtable]::new([System.StringComparer]::Ordinal)
    foreach ($project in $Projects) {
        $id = [string]$project.id
        foreach ($artifact in @($project.artifacts)) {
            $parts = ([string]$artifact).Split(':')
            if ($parts.Count -lt 2) {
                throw "Unparsable artifact coordinate in project '$id': $artifact"
            }
            $key = $parts[0] + ':' + $parts[1]
            if ($artifacts.ContainsKey($key)) {
                throw "Artifact '$key' is published by both '$($artifacts[$key])' and '$id': with two owners the build order no longer determines which bits land in the local repository."
            }
            $artifacts[$key] = $id
        }
    }

    return $artifacts
}

function Assert-ManifestGraphMatchesPoms {
    param(
        [object[]]$Projects,
        [hashtable]$WorkspaceArtifacts,
        [string]$ProjectDirectory
    )

    $problems = [System.Collections.Generic.List[string]]::new()
    foreach ($project in $Projects) {
        $id = [string]$project.id
        $basePath = [System.IO.Path]::GetFullPath((Join-Path $ProjectDirectory ([string]$project.relativePath)))
        if (-not (Test-Path -LiteralPath $basePath -PathType Container)) {
            continue
        }

        $declared = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        foreach ($dependency in @($project.dependsOn)) {
            [void]$declared.Add([string]$dependency)
        }

        $pomPaths = [System.Collections.Generic.List[string]]::new()
        foreach ($step in $project.buildSteps) {
            $pomPaths.Add([System.IO.Path]::GetFullPath((Join-Path $basePath ([string]$step.pom))))
        }

        $actual = Get-PomReferencedWorkspaceProjects $pomPaths.ToArray() $WorkspaceArtifacts $id
        $missing = @($actual | Where-Object { -not $declared.Contains([string]$_) } | Sort-Object)
        $extra = @($declared | Where-Object { -not $actual.Contains([string]$_) } | Sort-Object)

        if ($missing.Count -gt 0) {
            $problems.Add("$id`: pom references $($missing -join ', '), but dependsOn does not declare it - the declared build order would not hold on an empty local repository")
        }
        if ($extra.Count -gt 0) {
            $problems.Add("$id`: dependsOn declares $($extra -join ', '), but no pom references it - the declared graph has drifted from Maven")
        }
    }

    if ($problems.Count -gt 0) {
        throw ($problems -join [Environment]::NewLine)
    }
}

function Get-BuildOrder {
    param([object[]]$Projects)

    $byId = @{}
    foreach ($project in $Projects) {
        $id = [string]$project.id
        if ($byId.ContainsKey($id)) {
            throw "Manifest lists project '$id' more than once."
        }
        $byId[$id] = $project
    }

    $dependencies = @{}
    foreach ($project in $Projects) {
        $id = [string]$project.id
        $declared = [System.Collections.Generic.List[string]]::new()
        foreach ($dependency in @($project.dependsOn)) {
            $dependencyId = [string]$dependency
            if (-not $byId.ContainsKey($dependencyId)) {
                throw "Project '$id' depends on '$dependencyId', which the manifest does not define."
            }
            if ($dependencyId -eq $id) {
                throw "Project '$id' depends on itself."
            }
            $declared.Add($dependencyId)
        }
        $dependencies[$id] = $declared
    }

    $remaining = [System.Collections.Generic.List[string]]::new()
    foreach ($project in $Projects) {
        $remaining.Add([string]$project.id)
    }

    $order = [System.Collections.Generic.List[string]]::new()
    while ($remaining.Count -gt 0) {
        $progress = $false
        foreach ($id in @($remaining)) {
            $ready = $true
            foreach ($dependency in $dependencies[$id]) {
                if ($remaining.Contains($dependency)) {
                    $ready = $false
                    break
                }
            }
            if ($ready) {
                $order.Add($id)
                [void]$remaining.Remove($id)
                $progress = $true
            }
        }
        if (-not $progress) {
            throw "Dependency cycle in the manifest between: $($remaining -join ', ')"
        }
    }

    return [pscustomobject]@{
        Order = $order
        Projects = $byId
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

if ($FreshLocalRepository) {
    if ($Offline) {
        throw 'An empty local repository cannot be filled offline: drop -Offline, because the first build has to resolve third-party artifacts from a remote repository.'
    }
    if (-not $LocalRepository) {
        # Отдельный каталог, а не .local-maven-repository: тот держит тёплый кэш, ради которого
        # и существует -Offline, и сносить его ради проверки с нуля нельзя. Имя в .gitignore.
        $LocalRepository = '.local-maven-repository-fresh'
    }
    Write-Host 'Fresh local repository: every dependency must come from a remote repository, so this run is the release-time reproducibility gate, not an inner-loop check.'
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
    $mustBeEmpty = $RequireEmptyLocalRepository.IsPresent -or $FreshLocalRepository.IsPresent
    if (Test-Path -LiteralPath $resolvedLocalRepository -PathType Container) {
        if ($mustBeEmpty -and (Get-ChildItem -LiteralPath $resolvedLocalRepository -Force | Select-Object -First 1)) {
            $hint = ''
            if ($FreshLocalRepository) {
                $hint = " Delete it, or pass -LocalRepository with another (ignored) path, to re-run the empty-repository bootstrap."
            }
            throw "Local repository must be empty: $resolvedLocalRepository.$hint"
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

$workspaceArtifacts = Assert-PublishedCoordinatesAreUnique $manifest.projects
Write-Host "OK coordinates: $($workspaceArtifacts.Count) published artifacts, each with a single owner"
Assert-ManifestGraphMatchesPoms $manifest.projects $workspaceArtifacts $projectDirectory
$edgeCount = 0
foreach ($project in $manifest.projects) {
    $edgeCount += @($project.dependsOn).Count
}
Write-Host "OK graph: $edgeCount dependsOn edges match the POM dependency graph"

$buildOrder = Get-BuildOrder $manifest.projects
$resolvedProjects = @{}
foreach ($project in $manifest.projects) {
    $basePath = [System.IO.Path]::GetFullPath((Join-Path $projectDirectory ([string]$project.relativePath)))
    if (-not (Test-Path -LiteralPath $basePath -PathType Container)) {
        throw "Required sibling project '$($project.id)' not found: $basePath"
    }
    $resolvedProjects[[string]$project.id] = $basePath

    $fingerprintAlgorithm = [string]$project.fingerprint.algorithm
    if ([string]$project.source.kind -eq 'in-repo-project' -and $fingerprintAlgorithm -ne 'gitvaa-source-tree-sha256-v2') {
        throw "Project '$($project.id)' lives in this checkout but its fingerprint uses '$fingerprintAlgorithm': in-repo projects must use the deterministic v2 algorithm."
    }

    $actualFingerprint = Get-SourceTreeFingerprint $basePath $project.fingerprint.inputs $fingerprintAlgorithm
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

$declaredOrder = @($manifest.projects | ForEach-Object { [string]$_.id })
$effectiveOrder = @($buildOrder.Order)
if (($declaredOrder -join '|') -ne ($effectiveOrder -join '|')) {
    Write-Host "Build order: $($effectiveOrder -join ' -> ') (JSON order is not topological; dependsOn wins)"
} else {
    Write-Host "Build order: $($effectiveOrder -join ' -> ')"
}

if ($ValidateOnly) {
    if ($resolvedLocalRepository) {
        Write-Host "OK local repository: $resolvedLocalRepository is empty"
    }
    Write-Host 'Manifest, dependsOn graph, POM edges, published coordinates, workspace layout, source fingerprints and toolchain are valid.'
    exit 0
}

foreach ($projectId in $effectiveOrder) {
    $project = $buildOrder.Projects[$projectId]
    $basePath = [string]$resolvedProjects[$projectId]
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
