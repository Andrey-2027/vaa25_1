[CmdletBinding()]
param(
    [string]$SourceFile,
    [string]$SourceDirectory,
    [string]$RelativePath,
    [string]$ExpectedSha256,
    [string]$SpecFile,
    [string]$Scenario,
    [string]$PlanReceipt,
    [string]$ManifestFile,
    [ValidateSet('generated-acceptance', 'selftest')]
    [string]$Mode = 'generated-acceptance',
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = (Join-Path $env:USERPROFILE '.m2\repository'),
    [string]$Settings,
    [string]$OutputDirectory,
    [string]$IsolationRoot = [IO.Path]::GetTempPath(),
    [string]$RunId,
    [switch]$Online,
    [switch]$KeepIsolation,
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:SupportRoot = Join-Path $PSScriptRoot 'entity-generation-g1'
$script:ConsumerTemplate = Join-Path $script:SupportRoot 'consumer'
$script:FixturesRoot = Join-Path $script:SupportRoot 'fixtures'
$script:ControlSpec = Join-Path $script:ConsumerTemplate 'src\test\java\com\example\generated\harness\ScenarioSpec.java'
$script:ControlDocumentSpec = Join-Path $script:SupportRoot 'scenarios\control-document\ScenarioSpec.java'
$script:MultiFileSpec = Join-Path $script:SupportRoot 'scenarios\multifile\ScenarioSpec.java'
$script:MultiFileTest = Join-Path $script:SupportRoot 'multifile\MultiFileScenarioTest.java'

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $script:RepoRoot 'outputs\entity-generation-g1\g1-2'
}
if ([string]::IsNullOrWhiteSpace($LocalRepository)) {
    $LocalRepository = Join-Path $env:USERPROFILE '.m2\repository'
}

function Write-Utf8Text {
    param([string]$Path, [string]$Text)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $Text, $encoding)
}

function Resolve-RepoPath {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $Path }
    if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
    return [IO.Path]::GetFullPath((Join-Path $script:RepoRoot $Path))
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Text {
    param([string]$Path)
    return [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8)
}

function Reject {
    param([string]$Reason, [string]$Message)
    throw ("REJECT|" + $Reason + "|" + $Message)
}

function Quote-Argument {
    param([string]$Value)
    if ($Value -match '[\s"]') {
        return '"' + ($Value -replace '"', '\"') + '"'
    }
    return $Value
}

function Get-JavaVersion {
    param([string]$JdkHome)
    $start = New-Object Diagnostics.ProcessStartInfo
    $start.FileName = Join-Path $JdkHome 'bin\java.exe'
    $start.Arguments = '-version'
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    $text = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    return $text.Trim()
}

function Get-GitHead {
    try {
        $value = & git -C $script:RepoRoot rev-parse HEAD 2>$null
        if ($LASTEXITCODE -eq 0) { return ([string]$value).Trim() }
    } catch {
        # Evidence is optional when the checkout is not a Git worktree.
    }
    return ''
}

function New-Isolation {
    $parent = [IO.Path]::GetFullPath($IsolationRoot)
    $repo = [IO.Path]::GetFullPath($script:RepoRoot)
    if ($parent.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) {
        Reject 'isolation-inside-repository' 'IsolationRoot must be outside the repository'
    }
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $directory = Join-Path $parent ('entity-generation-g1-harness-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $directory -Force | Out-Null
    Copy-Item -Path (Join-Path $script:ConsumerTemplate '*') -Destination $directory -Recurse -Force
    return $directory
}

function Assert-SourceContract {
    param([string]$Source, [string]$Relative)
    $relative = $Relative -replace '\\', '/'
    if ($relative -notmatch '^(?:[A-Za-z_][A-Za-z0-9_]*/)+[A-Za-z_][A-Za-z0-9_]*\.java$') {
        Reject 'relative-path-invalid' ("RelativePath must be <package>/<Class>.java: " + $Relative)
    }
    $bytes = [IO.File]::ReadAllBytes($Source)
    if ($bytes.Length -eq 0) { Reject 'source-format-invalid' 'Source is empty' }
    if ($bytes.Length -ge 3 -and $bytes[0] -eq 0xEF -and $bytes[1] -eq 0xBB -and $bytes[2] -eq 0xBF) {
        Reject 'source-format-invalid' 'Source must not start with a UTF-8 BOM'
    }
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    if ($text.Contains("`r")) { Reject 'source-format-invalid' 'Source must use LF, not CRLF/CR' }
    if (-not $text.EndsWith("`n")) { Reject 'source-format-invalid' 'Source must end with exactly one LF' }
    if ($text.EndsWith("`n`n")) { Reject 'source-format-invalid' 'Source must end with exactly one LF' }

    $segments = $relative.Split('/')
    $expectedPackage = ($segments[0..($segments.Length - 2)] -join '.')
    $expectedClass = [IO.Path]::GetFileNameWithoutExtension($relative)
    $packageMatch = [regex]::Match($text, '(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;')
    if (-not $packageMatch.Success) { Reject 'source-format-invalid' 'Source has no package declaration' }
    if ($packageMatch.Groups[1].Value -ne $expectedPackage) {
        $message = "Package " + $packageMatch.Groups[1].Value +
            " does not match relative path " + $expectedPackage
        Reject 'source-format-invalid' $message
    }
    $classPattern = '(?m)^\s*public\s+class\s+' + [regex]::Escape($expectedClass) + '\b'
    if (-not [regex]::IsMatch($text, $classPattern)) {
        Reject 'source-format-invalid' ("Source does not declare public class " + $expectedClass)
    }
    return [pscustomobject]@{
        Bytes = $bytes.Length
        Package = $expectedPackage
        ClassName = $expectedClass
    }
}

function Get-SpecMetadata {
    param([string]$Spec)
    if (-not (Test-Path -LiteralPath $Spec)) { Reject 'spec-missing' ("Spec not found: " + $Spec) }
    $text = Get-Text $Spec
    $package = [regex]::Match($text, '(?m)^\s*package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*;').Groups[1].Value
    if ($package -ne 'com.example.generated.harness') {
        Reject 'spec-invalid' 'ScenarioSpec must be in com.example.generated.harness'
    }
    $scenario = [regex]::Match($text, 'static\s+final\s+String\s+SCENARIO\s*=\s*"([^"]*)"').Groups[1].Value
    $className = [regex]::Match($text, 'static\s+final\s+String\s+CLASS_NAME\s*=\s*"([^"]*)"').Groups[1].Value
    $relative = [regex]::Match($text, 'static\s+final\s+String\s+RELATIVE_PATH\s*=\s*"([^"]*)"').Groups[1].Value
    if ([string]::IsNullOrWhiteSpace($scenario) -or
            [string]::IsNullOrWhiteSpace($className) -or
            [string]::IsNullOrWhiteSpace($relative)) {
        Reject 'spec-invalid' 'ScenarioSpec must declare SCENARIO, CLASS_NAME and RELATIVE_PATH'
    }
    return [pscustomobject]@{
        Scenario = $scenario
        ClassName = $className
        RelativePath = $relative
        ReferenceScenario = ($text -match 'REQUIRED_SUITE\s*=\s*"com.example.generated.harness.ReferenceScenarioTest"')
        OwnerScenario = ($text -match 'REQUIRED_SUITE\s*=\s*"com.example.generated.harness.OwnerScenarioTest"')
        SectionTargets = ($text -match 'SUPPORT_PROFILE\s*=\s*"g1-sections"')
        SectionScenario = ($text -match 'REQUIRED_SUITE\s*=\s*"com.example.generated.harness.SectionsScenarioTest"')
    }
}

function Get-SurefireSummary {
    param([string]$Isolation, [string[]]$RequiredSuites)
    $reportDir = Join-Path $Isolation 'target\surefire-reports'
    $reports = @()
    if (Test-Path -LiteralPath $reportDir) {
        $reports = @(Get-ChildItem -LiteralPath $reportDir -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue |
            Sort-Object Name)
    }
    $counts = [ordered]@{
        reports = $reports.Count
        run = 0
        failures = 0
        errors = 0
        skipped = 0
        suites = @()
    }
    $suites = New-Object System.Collections.Generic.List[object]
    foreach ($report in $reports) {
        [xml]$xml = Get-Content -LiteralPath $report.FullName -Raw
        $suite = $xml.testsuite
        if ($null -eq $suite) { continue }
        $name = [string]$suite.name
        $entry = [ordered]@{
            name = $name
            required = ($RequiredSuites -contains $name)
            run = [int]$suite.tests
            failures = [int]$suite.failures
            errors = [int]$suite.errors
            skipped = [int]$suite.skipped
        }
        $suites.Add($entry) | Out-Null
        $counts.run += $entry.run
        $counts.failures += $entry.failures
        $counts.errors += $entry.errors
        $counts.skipped += $entry.skipped
    }
    $counts.suites = @($suites | Sort-Object { $_.name })
    return $counts
}

function Add-BigEndian {
    param($Digest, [byte[]]$Bytes)
    [Array]::Reverse($Bytes)
    $Digest.TransformBlock($Bytes, 0, $Bytes.Length, $Bytes, 0) | Out-Null
}

function Add-FramedText {
    param($Digest, [string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    Add-BigEndian $Digest ([BitConverter]::GetBytes([int]$bytes.Length))
    $Digest.TransformBlock($bytes, 0, $bytes.Length, $bytes, 0) | Out-Null
}

function Get-ManifestFileSetHash {
    param($Entries)
    $digest = [Security.Cryptography.SHA256]::Create()
    try {
        Add-FramedText $digest 'g1-file-set-sha256-v2'
        Add-BigEndian $digest ([BitConverter]::GetBytes([int]$Entries.Count))
        foreach ($entry in $Entries) {
            Add-FramedText $digest ([string]$entry.relativePath)
            Add-FramedText $digest ([string]$entry.operation)
            Add-BigEndian $digest ([BitConverter]::GetBytes([long]$entry.bytes))
            Add-FramedText $digest ([string]$entry.sha256)
        }
        $digest.TransformFinalBlock([byte[]]::new(0), 0, 0) | Out-Null
        return ([BitConverter]::ToString($digest.Hash) -replace '-', '').ToLowerInvariant()
    } finally {
        $digest.Dispose()
    }
}

function Read-PlanManifest {
    param([string]$Path, [string]$RelativePath, [string]$SourceSha256, [long]$SourceBytes,
        [string]$SourceDirectory)
    if (-not (Test-Path -LiteralPath $Path)) { Reject 'manifest-missing' ('Manifest not found: ' + $Path) }
    try {
        $manifest = Get-Text $Path | ConvertFrom-Json
    } catch {
        Reject 'manifest-invalid' ('Manifest is not valid JSON: ' + $_.Exception.Message)
    }
    $properties = @($manifest.PSObject.Properties.Name)
    foreach ($name in @('kind', 'planId', 'recipe', 'generatorVersion', 'fingerprintProfile', 'entries', 'manifestSha256')) {
        if ($properties -notcontains $name) { Reject 'manifest-invalid' ('Manifest is missing ' + $name) }
    }
    if ([string]$manifest.kind -ne 'g1-generation-manifest-v2') {
        Reject 'manifest-invalid' ('Unsupported manifest kind: ' + $manifest.kind)
    }
    if ([string]$manifest.planId -notmatch '^g1-[0-9a-f]{64}$') {
        Reject 'manifest-invalid' ('Invalid planId: ' + $manifest.planId)
    }
    if ([string]$manifest.fingerprintProfile -ne 'plan-fingerprint-v2') {
        Reject 'manifest-invalid' ('Extension C requires plan-fingerprint-v2, got ' + $manifest.fingerprintProfile)
    }
    $entries = @($manifest.entries)
    if ($entries.Count -eq 0) {
        Reject 'manifest-invalid' 'Manifest entries must not be empty'
    }
    $seen = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $entries) {
        $entryProperties = @($entry.PSObject.Properties.Name)
        foreach ($name in @('operation', 'relativePath', 'sha256', 'bytes')) {
            if ($entryProperties -notcontains $name) {
                Reject 'manifest-invalid' ('Manifest entry is missing ' + $name)
            }
        }
        if ([string]$entry.operation -ne 'CREATE') {
            Reject 'manifest-invalid' ('C2 accepts only CREATE, got ' + $entry.operation)
        }
        $entryRelative = ([string]$entry.relativePath -replace '\\', '/')
        if ($entryRelative -notmatch '^(?:[A-Za-z_][A-Za-z0-9_]*/)+[A-Za-z_][A-Za-z0-9_]*\.java$') {
            Reject 'manifest-invalid' ('Entry path must be <package>/<Class>.java: ' + $entry.relativePath)
        }
        if (-not $seen.Add($entryRelative)) {
            Reject 'manifest-duplicate' ('Duplicate manifest path: ' + $entryRelative)
        }
        if ([string]$entry.sha256 -notmatch '^[0-9a-f]{64}$') {
            Reject 'manifest-invalid' ('Entry sha256 must be 64 lowercase hex characters: ' + $entry.relativePath)
        }
        if ([long]$entry.bytes -le 0) {
            Reject 'manifest-invalid' ('Entry bytes must be positive: ' + $entry.relativePath)
        }
    }
    if ([string]::IsNullOrWhiteSpace($SourceDirectory)) {
        if ($entries.Count -ne 1) {
            Reject 'manifest-scope-unsupported' ('N-entry manifest requires SourceDirectory, got ' + $entries.Count + ' entries')
        }
        $entry = $entries[0]
        if (([string]$entry.relativePath -replace '\\', '/') -ne ($RelativePath -replace '\\', '/')) {
            Reject 'manifest-mismatch' ('Manifest path ' + $entry.relativePath + ' does not match ' + $RelativePath)
        }
        if ([string]$entry.sha256 -ne $SourceSha256) {
            Reject 'manifest-mismatch' ('Manifest sha256 ' + $entry.sha256 + ' does not match ' + $SourceSha256)
        }
        if ([long]$entry.bytes -ne $SourceBytes) {
            Reject 'manifest-mismatch' ('Manifest bytes ' + $entry.bytes + ' does not match ' + $SourceBytes)
        }
    } else {
        $sourceRoot = [IO.Path]::GetFullPath($SourceDirectory)
        if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) {
            Reject 'source-directory-missing' ('SourceDirectory not found: ' + $sourceRoot)
        }
        $separator = [IO.Path]::DirectorySeparatorChar
        $rootWithSeparator = $sourceRoot.TrimEnd('\', '/') + $separator
        $declared = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $entries) {
            $entryRelative = ([string]$entry.relativePath -replace '\\', '/')
            $full = [IO.Path]::GetFullPath((Join-Path $sourceRoot ($entryRelative -replace '/', '\')))
            if (-not $full.StartsWith($rootWithSeparator, [StringComparison]::OrdinalIgnoreCase)) {
                Reject 'manifest-path-invalid' ('Entry escapes SourceDirectory: ' + $entry.relativePath)
            }
            if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
                Reject 'manifest-source-missing' ('Manifest source is missing: ' + $entry.relativePath)
            }
            $declared.Add($entryRelative) | Out-Null
            $actualHash = Get-Sha256 $full
            $actualBytes = (Get-Item -LiteralPath $full).Length
            if ($actualHash -ne [string]$entry.sha256) {
                Reject 'manifest-mismatch' ('Manifest sha256 ' + $entry.sha256 + ' does not match ' + $entry.relativePath)
            }
            if ([long]$entry.bytes -ne $actualBytes) {
                Reject 'manifest-mismatch' ('Manifest bytes ' + $entry.bytes + ' does not match ' + $entry.relativePath)
            }
        }
        $actualFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' -File |
            Sort-Object FullName)
        foreach ($file in $actualFiles) {
            $actualRelative = ($file.FullName.Substring($sourceRoot.Length + 1) -replace '\\', '/')
            if (-not $declared.Contains($actualRelative)) {
                Reject 'manifest-source-unexpected' ('Source is not declared by the manifest: ' + $actualRelative)
            }
        }
    }
    $fileSetHash = Get-ManifestFileSetHash $entries
    if ($fileSetHash -ne [string]$manifest.manifestSha256) {
        Reject 'manifest-mismatch' ('Manifest hash ' + $manifest.manifestSha256 + ' does not match ' + $fileSetHash)
    }
    return $manifest
}

function Get-PlatformJarHashes {
    param([string]$Repository)
    $result = @{}
    $root = Join-Path $Repository 'org\ipro'
    if (-not (Test-Path -LiteralPath $root)) { return $result }
    $jars = @(Get-ChildItem -LiteralPath $root -Recurse -Filter '*.jar' -File -ErrorAction SilentlyContinue)
    foreach ($jar in $jars) {
        $result[$jar.FullName] = Get-Sha256 $jar.FullName
    }
    return $result
}

function Get-UsedPlatformJars {
    param([string]$TreeFile, [string]$Repository)
    $result = @{}
    if (-not (Test-Path -LiteralPath $TreeFile)) { return $result }
    $tree = Get-Content -LiteralPath $TreeFile -Raw | ConvertFrom-Json
    $queue = New-Object System.Collections.Queue
    $queue.Enqueue($tree)
    while ($queue.Count -gt 0) {
        $node = $queue.Dequeue()
        if ($null -eq $node) { continue }
        $nodeProperties = @($node.PSObject.Properties.Name)
        if (($nodeProperties -contains 'groupId') -and $node.groupId -eq 'org.ipro' -and
                ($nodeProperties -contains 'type') -and $node.type -eq 'jar') {
            $classifier = if ($nodeProperties -contains 'classifier') { [string]$node.classifier } else { '' }
            $file = [string]$node.artifactId + '-' + [string]$node.version +
                $(if ([string]::IsNullOrWhiteSpace($classifier)) { '' } else { '-' + $classifier }) + '.jar'
            $jar = Join-Path $Repository ('org\ipro\' + $node.artifactId + '\' +
                $node.version + '\' + $file)
            if (-not $result.ContainsKey($jar)) {
                $result[$jar] = ([string]$node.groupId + ':' + [string]$node.artifactId + ':' +
                    [string]$node.type + ':' + $classifier + ':' + [string]$node.version)
            }
        }
        if ($nodeProperties -contains 'children') {
            foreach ($child in $node.children) { $queue.Enqueue($child) }
        }
    }
    return $result
}

function Invoke-MavenTest {
    param(
        [string]$Isolation,
        [string]$RunDirectory,
        [string[]]$ExtraArgs
    )
    $stdout = Join-Path $RunDirectory 'maven.stdout.log'
    $stderr = Join-Path $RunDirectory 'maven.stderr.log'
    $arguments = @('-B', '-ntp', '-Dstyle.color=never', '-f', (Join-Path $Isolation 'pom.xml'))
    if (-not [string]::IsNullOrWhiteSpace($Settings)) { $arguments += @('-s', $Settings) }
    if (-not [string]::IsNullOrWhiteSpace($LocalRepository)) {
        $arguments += ('-Dmaven.repo.local=' + $LocalRepository)
    }
    if (-not $Online) { $arguments += '-o' }
    $arguments += 'test'
    if ($null -ne $ExtraArgs) { $arguments += $ExtraArgs }
    $argumentLine = ($arguments | ForEach-Object { Quote-Argument ([string]$_) }) -join ' '

    $oldJavaHome = $env:JAVA_HOME
    $oldPath = $env:PATH
    try {
        $env:JAVA_HOME = $JavaHome
        $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH
        $process = Start-Process -FilePath $Maven -ArgumentList $argumentLine -NoNewWindow -Wait `
            -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $exitCode = $process.ExitCode
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        $env:PATH = $oldPath
    }
    $combined = ''
    if (Test-Path -LiteralPath $stdout) { $combined += (Get-Content -LiteralPath $stdout -Raw) }
    if (Test-Path -LiteralPath $stderr) { $combined += (Get-Content -LiteralPath $stderr -Raw) }
    Write-Utf8Text (Join-Path $RunDirectory 'maven.log') $combined
    return $exitCode
}

function Write-Summary {
    param([string]$RunDirectory, $Summary)
    $json = $Summary | ConvertTo-Json -Depth 12
    Write-Utf8Text (Join-Path $RunDirectory 'summary.json') ($json + "`n")
    $line = [string]$Summary.status
    if (-not [string]::IsNullOrWhiteSpace([string]$Summary.failureReason)) {
        $line += ': ' + [string]$Summary.failureReason
    }
    Write-Utf8Text (Join-Path $RunDirectory 'status.txt') ($line + "`n")
}

function Invoke-Acceptance {
    param(
        [string]$SourceFile,
        [string]$SourceDirectory,
        [string]$RelativePath,
        [string]$ExpectedSha256,
        [string]$SpecFile,
        [string]$Scenario,
        [string]$PlanReceipt,
        [string]$ManifestFile,
        [string]$Mode,
        [string]$RunDirectory,
        [string[]]$MavenExtraArgs = @(),
        [switch]$AllowMissingPlanReceipt,
        [switch]$KeepIsolation
    )

    New-Item -ItemType Directory -Path $RunDirectory -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $RunDirectory 'reports') -Force | Out-Null
    $isolation = ''
    $summary = [ordered]@{
        kind = 'g1-c-summary-v2'
        mode = $Mode
        scenario = [ordered]@{
            id = $Scenario
            specSha256 = ''
        }
        manifest = $null
        dependencies = @()
        supportSources = @()
        compile = [ordered]@{
            attempted = $false
            exitCode = -1
        }
        runtime = [ordered]@{
            attempted = $false
            startup = 'not-attempted'
        }
        status = 'failure'
        failureKind = ''
        failureReason = ''
        detail = ''
        sources = @()
        sourceSetSha256 = ''
        source = [ordered]@{
            file = $SourceFile
            relativePath = $RelativePath
            sha256 = ''
            expectedSha256 = $ExpectedSha256
            bytes = 0
            archived = ''
        }
        plan = [ordered]@{
            receipt = $PlanReceipt
            planId = ''
            recipe = ''
            generatorVersion = ''
            generatorRevision = ''
            sourceFixture = ''
        }
        harness = [ordered]@{
            isolation = ''
            javaHome = $JavaHome
            javaVersion = ''
            maven = $Maven
            offline = (-not $Online)
            localRepository = $LocalRepository
        }
        maven = [ordered]@{
            attempted = $false
            exitCode = $null
        }
        tests = [ordered]@{
            reports = 0
            run = 0
            failures = 0
            errors = 0
            skipped = 0
            suites = @()
        }
        platformJars = [ordered]@{
            used = 0
            changed = @()
            missing = @()
            file = ''
        }
        timestamp = (Get-Date).ToString('o')
        gitHead = (Get-GitHead)
    }

    try {
        $spec = Get-SpecMetadata $SpecFile
        if (-not [string]::IsNullOrWhiteSpace($Scenario) -and $Scenario -ne $spec.Scenario) {
            Reject 'spec-mismatch' ('Scenario ' + $Scenario + ' does not match spec ' + $spec.Scenario)
        }
        $summary.scenario.id = $spec.Scenario
        $summary.scenario.specSha256 = Get-Sha256 $SpecFile
        $manifest = $null
        $generatedEntries = @()
        $sourceDirectoryProvided = -not [string]::IsNullOrWhiteSpace($SourceDirectory)
        if ($sourceDirectoryProvided) {
            $SourceDirectory = Resolve-RepoPath $SourceDirectory
            if (-not (Test-Path -LiteralPath $SourceDirectory -PathType Container)) {
                Reject 'source-directory-missing' ('SourceDirectory not found: ' + $SourceDirectory)
            }
            if ([string]::IsNullOrWhiteSpace($ManifestFile)) {
                Reject 'manifest-required' 'ManifestFile is required for a multi-file acceptance'
            }
            if (-not [string]::IsNullOrWhiteSpace($PlanReceipt)) {
                Reject 'plan-receipt-unsupported' 'A multi-file acceptance reads the v2 manifest, not a legacy plan receipt'
            }
            $manifest = Read-PlanManifest -Path $ManifestFile -SourceDirectory $SourceDirectory
            $generatedEntries = @($manifest.entries)
            $rootEntry = $generatedEntries[0]
            $RelativePath = ([string]$rootEntry.relativePath -replace '\\', '/')
            $SourceFile = Join-Path $SourceDirectory ($RelativePath -replace '/', '\')
            $ExpectedSha256 = [string]$rootEntry.sha256
            $actualSha = Get-Sha256 $SourceFile
            $summary.source.file = $SourceFile
            $summary.source.relativePath = $RelativePath
            $summary.source.sha256 = $actualSha
            $summary.source.expectedSha256 = $ExpectedSha256
            $summary.source.bytes = (Get-Item -LiteralPath $SourceFile).Length
            $summary.sourceSetSha256 = [string]$manifest.manifestSha256
            $summary.manifest = $manifest
            $summary.plan.planId = [string]$manifest.planId
            $summary.plan.recipe = ([string]$manifest.recipe.id + '@' + [string]$manifest.recipe.version)
            $summary.plan.generatorVersion = [string]$manifest.generatorVersion
            $summary.sources = @(foreach ($entry in $generatedEntries) {
                [ordered]@{
                    relativePath = ([string]$entry.relativePath -replace '\\', '/')
                    sha256 = [string]$entry.sha256
                    bytes = [long]$entry.bytes
                }
            })
            Copy-Item -LiteralPath $ManifestFile -Destination (Join-Path $RunDirectory 'manifest.json') -Force
        } else {
            if ([string]::IsNullOrWhiteSpace($SourceFile)) { Reject 'source-missing' 'SourceFile is required' }
            if (-not (Test-Path -LiteralPath $SourceFile)) {
                Reject 'source-missing' ('Source not found: ' + $SourceFile)
            }
            if ([string]::IsNullOrWhiteSpace($ExpectedSha256)) {
                Reject 'expected-sha256-required' 'ExpectedSha256 is required for generated acceptance'
            }
            $ExpectedSha256 = $ExpectedSha256.Trim().ToLowerInvariant()
            if ($ExpectedSha256 -notmatch '^[0-9a-f]{64}$') {
                Reject 'expected-sha256-invalid' 'ExpectedSha256 must be 64 lowercase hex characters'
            }
            $actualSha = Get-Sha256 $SourceFile
            $summary.source.sha256 = $actualSha
            $summary.source.bytes = (Get-Item -LiteralPath $SourceFile).Length
            if ($actualSha -ne $ExpectedSha256) {
                Reject 'source-hash-mismatch' ('Expected ' + $ExpectedSha256 + ', actual ' + $actualSha)
            }

            if ([string]::IsNullOrWhiteSpace($ManifestFile)) {
                if ($Mode -eq 'generated-acceptance' -and [string]::IsNullOrWhiteSpace($PlanReceipt)) {
                    Reject 'manifest-required' 'ManifestFile is required for generated acceptance'
                }
            } else {
                $manifest = Read-PlanManifest -Path $ManifestFile -RelativePath $RelativePath `
                    -SourceSha256 $actualSha -SourceBytes ([long]$summary.source.bytes)
                $summary.manifest = $manifest
                $summary.plan.planId = [string]$manifest.planId
                $summary.plan.recipe = ([string]$manifest.recipe.id + '@' + [string]$manifest.recipe.version)
                $summary.plan.generatorVersion = [string]$manifest.generatorVersion
                $summary.sourceSetSha256 = [string]$manifest.manifestSha256
                Copy-Item -LiteralPath $ManifestFile -Destination (Join-Path $RunDirectory 'manifest.json') -Force
            }

            if ([string]::IsNullOrWhiteSpace($PlanReceipt)) {
                if (-not $AllowMissingPlanReceipt -and $Mode -eq 'generated-acceptance' -and
                        $null -eq $summary.manifest) {
                    Reject 'plan-receipt-required' 'PlanReceipt or ManifestFile is required for generated acceptance'
                }
            } else {
                if (-not (Test-Path -LiteralPath $PlanReceipt)) {
                    Reject 'plan-receipt-missing' ('Plan receipt not found: ' + $PlanReceipt)
                }
                $planText = Get-Text $PlanReceipt
                $plan = $planText | ConvertFrom-Json
                $planProperties = @($plan.PSObject.Properties.Name)
                if (($planProperties -notcontains 'sha256') -or ($planProperties -notcontains 'relativePath')) {
                    Reject 'plan-receipt-incomplete' 'Plan receipt must contain sha256 and relativePath'
                }
                $planSha = ([string]$plan.sha256).ToLowerInvariant()
                if ($planSha -ne $actualSha) {
                    Reject 'plan-receipt-mismatch' ('Plan sha256 ' + $planSha + ' does not match ' + $actualSha)
                }
                if (([string]$plan.relativePath -replace '\\', '/') -ne ($RelativePath -replace '\\', '/')) {
                    Reject 'plan-receipt-mismatch' ('Plan relativePath ' + $plan.relativePath +
                        ' does not match ' + $RelativePath)
                }
                if ($planProperties -contains 'scenario' -and ([string]$plan.scenario) -ne $spec.Scenario) {
                    Reject 'plan-receipt-mismatch' ('Plan scenario ' + $plan.scenario +
                        ' does not match spec ' + $spec.Scenario)
                }
                Copy-Item -LiteralPath $PlanReceipt -Destination (Join-Path $RunDirectory 'plan-receipt.json') -Force
                if ($planProperties -contains 'planId') { $summary.plan.planId = [string]$plan.planId }
                if ($planProperties -contains 'recipe') { $summary.plan.recipe = [string]$plan.recipe }
                if ($planProperties -contains 'generatorVersion') {
                    $summary.plan.generatorVersion = [string]$plan.generatorVersion
                }
                if ($planProperties -contains 'generatorRevision') {
                    $summary.plan.generatorRevision = [string]$plan.generatorRevision
                }
                if ($planProperties -contains 'sourceFixture') {
                    $summary.plan.sourceFixture = [string]$plan.sourceFixture
                }
            }

            if ($null -ne $summary.manifest) {
                $generatedEntries = @($summary.manifest.entries)
            } else {
                $generatedEntries = @([ordered]@{
                    operation = 'CREATE'
                    relativePath = $RelativePath
                    sha256 = $actualSha
                    bytes = [long]$summary.source.bytes
                })
            }
            $summary.sources = @(foreach ($entry in $generatedEntries) {
                [ordered]@{
                    relativePath = ([string]$entry.relativePath -replace '\\', '/')
                    sha256 = [string]$entry.sha256
                    bytes = [long]$entry.bytes
                }
            })
            if ([string]::IsNullOrWhiteSpace([string]$summary.sourceSetSha256)) {
                $summary.sourceSetSha256 = Get-ManifestFileSetHash $generatedEntries
            }
        }

        $sourceContract = Assert-SourceContract -Source $SourceFile -Relative $RelativePath
        $summary.source.relativePath = $RelativePath
        if (($spec.RelativePath -replace '\\', '/') -ne ($RelativePath -replace '\\', '/')) {
            Reject 'spec-mismatch' ('Spec RELATIVE_PATH ' + $spec.RelativePath +
                ' does not match ' + $RelativePath)
        }
        $specSimpleName = $spec.ClassName.Split('.')[-1]
        if ($specSimpleName -ne $sourceContract.ClassName -or
                $spec.ClassName -ne ($sourceContract.Package + '.' + $sourceContract.ClassName)) {
            Reject 'spec-mismatch' ('Spec CLASS_NAME ' + $spec.ClassName +
                ' does not match source class ' + $sourceContract.Package + '.' + $sourceContract.ClassName)
        }

        if ($Mode -eq 'generated-acceptance' -and $null -eq $summary.manifest `
                -and -not [string]::IsNullOrWhiteSpace($PlanReceipt)) {
            $summary.kind = 'g1-c-summary-v1'
        }

        foreach ($entry in $generatedEntries) {
            $entryRelative = ([string]$entry.relativePath -replace '\\', '/')
            $entrySource = if ($sourceDirectoryProvided) {
                Join-Path $SourceDirectory ($entryRelative -replace '/', '\')
            } else {
                $SourceFile
            }
            $archivedSource = Join-Path $RunDirectory ('input\' + ($entryRelative -replace '/', '\'))
            New-Item -ItemType Directory -Path (Split-Path $archivedSource) -Force | Out-Null
            [IO.File]::WriteAllBytes($archivedSource, [IO.File]::ReadAllBytes($entrySource))
            if ($sourceDirectoryProvided -and $entryRelative -eq $RelativePath) {
                $summary.source.archived = $archivedSource
            }
        }
        if (-not $sourceDirectoryProvided) { $summary.source.archived = Join-Path $RunDirectory ('input\' + ($RelativePath -replace '/', '\')) }
        Copy-Item -LiteralPath $SpecFile -Destination (Join-Path $RunDirectory 'input\ScenarioSpec.java') -Force

        $beforeJars = Get-PlatformJarHashes $LocalRepository
        $isolation = New-Isolation
        $summary.harness.isolation = $isolation
        $summary.harness.javaVersion = Get-JavaVersion $JavaHome

        $targetSources = New-Object 'System.Collections.Generic.HashSet[string]' ([StringComparer]::OrdinalIgnoreCase)
        foreach ($entry in $generatedEntries) {
            $entryRelative = ([string]$entry.relativePath -replace '\\', '/')
            $entrySource = if ($sourceDirectoryProvided) {
                Join-Path $SourceDirectory ($entryRelative -replace '/', '\')
            } else {
                $SourceFile
            }
            $targetSource = Join-Path $isolation ('src\main\java\' + ($entryRelative -replace '/', '\'))
            New-Item -ItemType Directory -Path (Split-Path $targetSource) -Force | Out-Null
            [IO.File]::WriteAllBytes($targetSource, [IO.File]::ReadAllBytes($entrySource))
            if ((Get-Sha256 $targetSource) -ne [string]$entry.sha256) {
                Reject 'source-copy-mismatch' ('Copied source hash differs from the manifest: ' + $entryRelative)
            }
            $targetSources.Add($targetSource) | Out-Null
        }
        $targetSpec = Join-Path $isolation 'src\test\java\com\example\generated\harness\ScenarioSpec.java'
        [IO.File]::WriteAllBytes($targetSpec, [IO.File]::ReadAllBytes($SpecFile))
        if ((Get-Sha256 $targetSpec) -ne (Get-Sha256 $SpecFile)) {
            Reject 'spec-copy-mismatch' 'Copied spec differs from the input'
        }
        if ($spec.ReferenceScenario) {
            Copy-Item -LiteralPath (Join-Path $script:SupportRoot 'reference-tests/ReferenceScenarioTest.java') `
                -Destination (Join-Path $isolation 'src/test/java/com/example/generated/harness/ReferenceScenarioTest.java')
            $MavenExtraArgs += @('-Pg1-references', '-Dtest=ReferenceScenarioTest')
        }
        if ($spec.OwnerScenario) {
            Copy-Item -LiteralPath (Join-Path $script:SupportRoot 'owner-tests/OwnerScenarioTest.java') `
                -Destination (Join-Path $isolation 'src/test/java/com/example/generated/harness/OwnerScenarioTest.java')
            $MavenExtraArgs += @('-Pg1-owners', '-Dtest=OwnerScenarioTest')
        }
        if ($spec.SectionTargets) { $MavenExtraArgs += @('-Pg1-sections') }
        if ($spec.SectionScenario) {
            Copy-Item -LiteralPath (Join-Path $script:SupportRoot 'section-tests/SectionsScenarioTest.java') `
                -Destination (Join-Path $isolation 'src/test/java/com/example/generated/harness/SectionsScenarioTest.java')
            $MavenExtraArgs += @('-Dtest=SectionsScenarioTest')
        }
        $targetMultiTest = ''
        if ($generatedEntries.Count -gt 1 -and -not $spec.SectionScenario) {
            if (-not (Test-Path -LiteralPath $script:MultiFileTest)) {
                Reject 'harness-missing' ('Multi-file suite not found: ' + $script:MultiFileTest)
            }
            $targetMultiTest = Join-Path $isolation 'src\test\java\com\example\generated\harness\MultiFileScenarioTest.java'
            [IO.File]::WriteAllBytes($targetMultiTest, [IO.File]::ReadAllBytes($script:MultiFileTest))
        }

        $supportSources = New-Object System.Collections.Generic.List[object]
        foreach ($rootName in @('src\main\java', 'src\test\java')) {
            $rootPath = Join-Path $isolation $rootName
            if (-not (Test-Path -LiteralPath $rootPath)) { continue }
            $files = @(Get-ChildItem -LiteralPath $rootPath -Recurse -Filter '*.java' -File |
                Sort-Object FullName)
            foreach ($file in $files) {
                if ($targetSources.Contains($file.FullName) -or $file.FullName -eq $targetSpec -or
                        $file.FullName -eq $targetMultiTest) { continue }
                $relative = $file.FullName.Substring($isolation.Length + 1) -replace '\\', '/'
                $supportSources.Add([ordered]@{
                    relativePath = $relative
                    sha256 = Get-Sha256 $file.FullName
                    bytes = $file.Length
                }) | Out-Null
            }
        }
        $summary.supportSources = @($supportSources | Sort-Object { $_.relativePath })

        $requiredSuites = if ($generatedEntries.Count -gt 1) {
            @('com.example.generated.harness.MultiFileScenarioTest')
        } else {
            @('com.example.generated.harness.GenerationScenarioTest')
        }
        $effectiveMavenArgs = @($MavenExtraArgs)
        if ($generatedEntries.Count -gt 1 -and -not $spec.SectionScenario) {
            $effectiveMavenArgs += '-Dtest=com.example.generated.harness.MultiFileScenarioTest'
        }
        $summary.maven.attempted = $true
        $summary.compile.attempted = $true
        $mavenExit = Invoke-MavenTest -Isolation $isolation -RunDirectory $RunDirectory -ExtraArgs $effectiveMavenArgs
        $summary.maven.exitCode = $mavenExit
        $summary.compile.exitCode = $mavenExit
        if ($spec.ReferenceScenario) { $requiredSuites = @('com.example.generated.harness.ReferenceScenarioTest') }
        if ($spec.OwnerScenario) { $requiredSuites = @('com.example.generated.harness.OwnerScenarioTest') }
        if ($spec.SectionScenario) { $requiredSuites = @('com.example.generated.harness.SectionsScenarioTest') }
        $counts = Get-SurefireSummary -Isolation $isolation -RequiredSuites $requiredSuites
        $summary.tests = $counts
        $summary.runtime.attempted = ($counts.reports -gt 0)
        $reportDir = Join-Path $isolation 'target\surefire-reports'
        if (Test-Path -LiteralPath $reportDir) {
            Copy-Item -Path (Join-Path $reportDir '*') -Destination (Join-Path $RunDirectory 'reports') -Recurse -Force
        }
        foreach ($artifact in @('consumer-runtime-dependency-tree.json', 'consumer-test-classpath.txt')) {
            $artifactPath = Join-Path $isolation ('target\' + $artifact)
            if (Test-Path -LiteralPath $artifactPath) {
                Copy-Item -LiteralPath $artifactPath -Destination (Join-Path $RunDirectory $artifact) -Force
            }
        }

        $usedJars = Get-UsedPlatformJars -TreeFile (Join-Path $isolation 'target\consumer-runtime-dependency-tree.json') `
            -Repository $LocalRepository
        $afterJars = Get-PlatformJarHashes $LocalRepository
        $dependencies = New-Object System.Collections.Generic.List[object]
        $dependencyIds = New-Object System.Collections.Generic.List[string]
        foreach ($jarPath in $usedJars.Keys) {
            if (-not (Test-Path -LiteralPath $jarPath)) { continue }
            $dependencyIds.Add([string]$usedJars[$jarPath]) | Out-Null
            $dependencies.Add([ordered]@{
                id = [string]$usedJars[$jarPath]
                sha256 = $afterJars[$jarPath]
            }) | Out-Null
        }
        $summary.dependencies = @($dependencies | Sort-Object { $_.id })
        $jarLines = New-Object System.Collections.Generic.List[string]
        $changed = New-Object System.Collections.Generic.List[string]
        $missing = New-Object System.Collections.Generic.List[string]
        foreach ($jarPath in $usedJars.Keys) {
            if (-not (Test-Path -LiteralPath $jarPath)) {
                $missing.Add([string]$usedJars[$jarPath]) | Out-Null
                continue
            }
            $afterHash = $afterJars[$jarPath]
            $beforeHash = $beforeJars[$jarPath]
            if ($null -ne $beforeHash -and $beforeHash -ne $afterHash) {
                $changed.Add([string]$usedJars[$jarPath]) | Out-Null
            }
            $jarLines.Add($afterHash + '  ' + $jarPath) | Out-Null
        }
        $summary.platformJars.used = $usedJars.Count
        $summary.platformJars.changed = @($changed)
        $summary.platformJars.missing = @($missing)
        $summary.platformJars.file = 'platform-jars.sha256'
        Write-Utf8Text (Join-Path $RunDirectory 'platform-jars.sha256') (($jarLines -join "`n") + "`n")

        if ($mavenExit -ne 0) {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'maven-failed'
            $summary.detail = 'Maven exited with code ' + $mavenExit + '; see maven.log and reports'
        } elseif ($counts.run -eq 0) {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'no-tests'
            $summary.detail = 'Maven completed without executing a test; this is not a pass'
        } elseif ($counts.skipped -ne 0) {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'tests-skipped'
            $summary.detail = 'Surefire skipped=' + $counts.skipped + '; skipped checks are not a pass'
        } elseif ($counts.failures -ne 0 -or $counts.errors -ne 0) {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'tests-failed'
            $summary.detail = 'Surefire failures=' + $counts.failures + ', errors=' + $counts.errors
        } elseif ($missing.Count -ne 0) {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'platform-jar-missing'
            $summary.detail = 'Missing platform JARs: ' + (($missing | Sort-Object) -join ', ')
        } elseif ($changed.Count -ne 0) {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'platform-jar-changed'
            $summary.detail = 'Platform JARs changed during the run: ' + (($changed | Sort-Object) -join ', ')
        } elseif ($Mode -eq 'generated-acceptance' -and $null -eq $summary.manifest `
                -and [string]::IsNullOrWhiteSpace($PlanReceipt)) {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'manifest-required'
            $summary.detail = 'Generated acceptance requires a manifest or a legacy plan receipt'
        } elseif ($summary.dependencies.Count -eq 0) {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'dependencies-empty'
            $summary.detail = 'No effective platform/target JAR was recorded'
        } elseif (@($requiredSuites | Where-Object {
                    $requiredName = $_
                    -not ($summary.tests.suites | Where-Object {
                        $_.name -eq $requiredName -and $_.run -gt 0 -and $_.failures -eq 0 -and
                            $_.errors -eq 0 -and $_.skipped -eq 0
                    })
                }).Count -ne 0) {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'required-suite-missing'
            $missingSuites = @($requiredSuites | Where-Object {
                $requiredName = $_
                -not ($summary.tests.suites | Where-Object {
                    $_.name -eq $requiredName -and $_.run -gt 0 -and $_.failures -eq 0 -and
                        $_.errors -eq 0 -and $_.skipped -eq 0
                })
            })
            $summary.detail = 'Required suites did not run successfully: ' + ($missingSuites -join ', ')
        } else {
            $summary.status = 'success'
            $summary.failureReason = ''
            $summary.detail = 'Compile, startup, metadata and canonical runtime checks passed'
        }
        if ($counts.run -gt 0 -and $counts.failures -eq 0 -and $counts.errors -eq 0 -and $counts.skipped -eq 0) {
            $summary.runtime.startup = 'success'
        } elseif ($summary.compile.attempted -and $mavenExit -eq 0) {
            $summary.runtime.startup = 'failure'
        }
    } catch {
        $message = $_.Exception.Message
        if ($message -like 'REJECT|*') {
            $parts = $message.Split('|', 3)
            $summary.status = 'failure'
            $summary.failureKind = 'rejected'
            $summary.failureReason = $parts[1]
            $summary.detail = $parts[2]
        } else {
            $summary.status = 'failure'
            $summary.failureKind = 'harness'
            $summary.failureReason = 'harness-error'
            $summary.detail = $message
        }
    } finally {
        if (-not [string]::IsNullOrWhiteSpace($isolation) -and -not $KeepIsolation) {
            Remove-Item -LiteralPath $isolation -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    Write-Summary -RunDirectory $RunDirectory -Summary $summary
    return [pscustomobject]$summary
}

function New-MultiFileSource {
    param([string]$Destination, [ValidateSet('complete', 'missing', 'extra', 'changed', 'broken')]
        [string]$Variant = 'complete')
    $files = @(
        'com/example/generated/MultiFileRoot.java',
        'com/example/generated/MultiFileRow.java',
        'com/example/generated/MultiFileNote.java'
    )
    foreach ($relative in $files) {
        if ($Variant -eq 'missing' -and $relative -like '*MultiFileRow.java') { continue }
        if ($Variant -eq 'extra' -and $relative -like '*MultiFileNote.java') {
            # Keep all declared files; an extra file is added below.
        }
        $target = Join-Path $Destination ($relative -replace '/', '\')
        New-Item -ItemType Directory -Path (Split-Path $target) -Force | Out-Null
        Copy-Item -LiteralPath (Join-Path $script:FixturesRoot ('multifile\' + [IO.Path]::GetFileName($relative))) `
            -Destination $target -Force
    }
    $rowPath = Join-Path $Destination 'com\example\generated\MultiFileRow.java'
    if ($Variant -eq 'changed') {
        $text = [IO.File]::ReadAllText($rowPath, [Text.Encoding]::UTF8)
        [IO.File]::WriteAllText($rowPath, $text + "// changed after manifest\n", (New-Object System.Text.UTF8Encoding($false)))
    }
    if ($Variant -eq 'broken') {
        $text = [IO.File]::ReadAllText($rowPath, [Text.Encoding]::UTF8)
        $marker = 'public class MultiFileRow extends BaseEntity {'
        if (-not $text.Contains($marker)) { throw 'Cannot inject the multi-file compile probe' }
        $text = $text.Replace($marker, $marker + "`n    this is not valid java;")
        [IO.File]::WriteAllText($rowPath, $text, (New-Object System.Text.UTF8Encoding($false)))
    }
    if ($Variant -eq 'extra') {
        $extra = Join-Path $Destination 'com\example\generated\UnexpectedCatalog.java'
        $extraText = @'
package com.example.generated;

public class UnexpectedCatalog {
}
'@
        [IO.File]::WriteAllText($extra, ($extraText -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
    }
}

function New-MultiFileManifest {
    param([string]$SourceDirectory, [string]$ManifestPath,
        [string[]]$RelativePaths = @(
            'com/example/generated/MultiFileRoot.java',
            'com/example/generated/MultiFileRow.java',
            'com/example/generated/MultiFileNote.java'
        ))
    $entries = @(foreach ($relative in $RelativePaths) {
        $file = Join-Path $SourceDirectory ($relative -replace '/', '\')
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
            throw ('Cannot build the multi-file manifest; missing ' + $relative)
        }
        [ordered]@{
            operation = 'CREATE'
            relativePath = $relative
            sha256 = Get-Sha256 $file
            bytes = (Get-Item -LiteralPath $file).Length
        }
    })
    $manifest = [ordered]@{
        kind = 'g1-generation-manifest-v2'
        planId = ('g1-' + ('a' * 64))
        recipe = [ordered]@{ id = 'standard-document-v1'; version = 1 }
        generatorVersion = 'selftest-multifile-v1'
        fingerprintProfile = 'plan-fingerprint-v2'
        entries = $entries
        manifestSha256 = (Get-ManifestFileSetHash $entries)
    }
    New-Item -ItemType Directory -Path (Split-Path $ManifestPath) -Force | Out-Null
    Write-Utf8Text $ManifestPath (($manifest | ConvertTo-Json -Depth 8) + "`n")
    return $manifest
}

function Invoke-SelfTest {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $root = Join-Path $OutputDirectory ('harness-selftest-' + $stamp)
    New-Item -ItemType Directory -Path $root -Force | Out-Null

    $controlSource = Join-Path $script:FixturesRoot 'control\ControlCatalog.java'
    $controlHash = Get-Sha256 $controlSource
    $controlRelative = 'com/example/generated/ControlCatalog.java'

    $positive = Invoke-Acceptance -SourceFile $controlSource -RelativePath $controlRelative `
        -ExpectedSha256 $controlHash -SpecFile $script:ControlSpec -Scenario 'control' `
        -Mode 'selftest' -RunDirectory (Join-Path $root 'positive') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $documentSource = Join-Path $script:FixturesRoot 'control\ControlDocument.java'
    $documentRelative = 'com/example/generated/ControlDocument.java'
    $documentPositive = Invoke-Acceptance -SourceFile $documentSource -RelativePath $documentRelative `
        -ExpectedSha256 (Get-Sha256 $documentSource) -SpecFile $script:ControlDocumentSpec `
        -Scenario 'control-document' -Mode 'selftest' `
        -RunDirectory (Join-Path $root 'positive-document') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $brokenSource = Join-Path $script:FixturesRoot 'broken\BrokenCatalog.java'
    $broken = Invoke-Acceptance -SourceFile $brokenSource -RelativePath $controlRelative `
        -ExpectedSha256 (Get-Sha256 $brokenSource) -SpecFile $script:ControlSpec -Scenario 'control' `
        -Mode 'selftest' -RunDirectory (Join-Path $root 'broken-compile') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $hashRejected = Invoke-Acceptance -SourceFile $controlSource -RelativePath $controlRelative `
        -ExpectedSha256 ('0' * 64) -SpecFile $script:ControlSpec -Scenario 'control' `
        -Mode 'selftest' -RunDirectory (Join-Path $root 'hash-rejection') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $noTests = Invoke-Acceptance -SourceFile $controlSource -RelativePath $controlRelative `
        -ExpectedSha256 $controlHash -SpecFile $script:ControlSpec -Scenario 'control' `
        -Mode 'selftest' -RunDirectory (Join-Path $root 'no-tests') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation `
        -MavenExtraArgs @('-Dtest=NoSuchTest')

    $multiSource = Join-Path $root 'multifile-source'
    New-MultiFileSource -Destination $multiSource
    $multiManifestPath = Join-Path $root 'multifile-source\generation-manifest.json'
    $multiManifest = New-MultiFileManifest -SourceDirectory $multiSource -ManifestPath $multiManifestPath
    $multiPositive = Invoke-Acceptance -SourceDirectory $multiSource -ManifestFile $multiManifestPath `
        -SpecFile $script:MultiFileSpec -Scenario 'multifile' -Mode 'selftest' `
        -RunDirectory (Join-Path $root 'multifile-positive') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $multiMissingSource = Join-Path $root 'multifile-missing'
    New-MultiFileSource -Destination $multiMissingSource -Variant missing
    $multiMissing = Invoke-Acceptance -SourceDirectory $multiMissingSource -ManifestFile $multiManifestPath `
        -SpecFile $script:MultiFileSpec -Scenario 'multifile' -Mode 'selftest' `
        -RunDirectory (Join-Path $root 'multifile-missing-run') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $multiExtraSource = Join-Path $root 'multifile-extra'
    New-MultiFileSource -Destination $multiExtraSource -Variant extra
    $multiExtra = Invoke-Acceptance -SourceDirectory $multiExtraSource -ManifestFile $multiManifestPath `
        -SpecFile $script:MultiFileSpec -Scenario 'multifile' -Mode 'selftest' `
        -RunDirectory (Join-Path $root 'multifile-extra-run') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $multiDuplicateManifest = [ordered]@{
        kind = 'g1-generation-manifest-v2'
        planId = ('g1-' + ('b' * 64))
        recipe = [ordered]@{ id = 'standard-document-v1'; version = 1 }
        generatorVersion = 'selftest-multifile-v1'
        fingerprintProfile = 'plan-fingerprint-v2'
        entries = @($multiManifest.entries[0], $multiManifest.entries[1], $multiManifest.entries[0])
        manifestSha256 = (Get-ManifestFileSetHash @($multiManifest.entries[0], $multiManifest.entries[1], $multiManifest.entries[0]))
    }
    $multiDuplicatePath = Join-Path $root 'multifile-duplicate-manifest.json'
    Write-Utf8Text $multiDuplicatePath (($multiDuplicateManifest | ConvertTo-Json -Depth 8) + "`n")
    $multiDuplicate = Invoke-Acceptance -SourceDirectory $multiSource -ManifestFile $multiDuplicatePath `
        -SpecFile $script:MultiFileSpec -Scenario 'multifile' -Mode 'selftest' `
        -RunDirectory (Join-Path $root 'multifile-duplicate-run') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $multiChangedSource = Join-Path $root 'multifile-changed'
    New-MultiFileSource -Destination $multiChangedSource -Variant changed
    $multiChanged = Invoke-Acceptance -SourceDirectory $multiChangedSource -ManifestFile $multiManifestPath `
        -SpecFile $script:MultiFileSpec -Scenario 'multifile' -Mode 'selftest' `
        -RunDirectory (Join-Path $root 'multifile-changed-run') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $multiBrokenSource = Join-Path $root 'multifile-broken'
    New-MultiFileSource -Destination $multiBrokenSource -Variant broken
    $multiBrokenManifestPath = Join-Path $root 'multifile-broken\generation-manifest.json'
    New-MultiFileManifest -SourceDirectory $multiBrokenSource -ManifestPath $multiBrokenManifestPath | Out-Null
    $multiBroken = Invoke-Acceptance -SourceDirectory $multiBrokenSource -ManifestFile $multiBrokenManifestPath `
        -SpecFile $script:MultiFileSpec -Scenario 'multifile' -Mode 'selftest' `
        -RunDirectory (Join-Path $root 'multifile-broken-run') `
        -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation

    $originalConsumer = $script:ConsumerTemplate
    try {
        $skippedConsumer = Join-Path $root 'skipped-consumer'
        Copy-Item -LiteralPath $originalConsumer -Destination $skippedConsumer -Recurse
        $skippedTest = Join-Path $skippedConsumer 'src\test\java\com\example\generated\harness\GenerationScenarioTest.java'
        $skippedText = [IO.File]::ReadAllText($skippedTest, [Text.Encoding]::UTF8)
        $marker = 'void setUp() throws Exception {'
        if (-not $skippedText.Contains($marker)) { throw 'Cannot inject the all-skipped probe' }
        $probeLine = '        org.junit.jupiter.api.Assumptions.assumeTrue(false, "all-skipped C probe");'
        $skippedText = $skippedText.Replace($marker, $marker + "`n" + $probeLine)
        [IO.File]::WriteAllText($skippedTest, $skippedText, (New-Object System.Text.UTF8Encoding($false)))
        $script:ConsumerTemplate = $skippedConsumer
        $allSkipped = Invoke-Acceptance -SourceFile $controlSource -RelativePath $controlRelative `
            -ExpectedSha256 $controlHash -SpecFile $script:ControlSpec -Scenario 'control' `
            -Mode 'selftest' -RunDirectory (Join-Path $root 'all-skipped') `
            -AllowMissingPlanReceipt -KeepIsolation:$KeepIsolation
    } finally {
        $script:ConsumerTemplate = $originalConsumer
    }

    $checks = [ordered]@{
        positive = ($positive.status -eq 'success')
        documentPositive = ($documentPositive.status -eq 'success')
        brokenCompile = ($broken.status -ne 'success' -and $broken.maven.attempted -and
            $broken.maven.exitCode -ne 0)
        hashRejected = ($hashRejected.status -eq 'failure' -and
            $hashRejected.failureKind -eq 'rejected' -and
            $hashRejected.failureReason -eq 'source-hash-mismatch' -and
            -not $hashRejected.maven.attempted)
        noTests = ($noTests.status -ne 'success' -and $noTests.maven.attempted -and
            $noTests.maven.exitCode -ne 0 -and $noTests.tests.run -eq 0)
        allSkipped = ($allSkipped.status -ne 'success' -and
            $allSkipped.failureReason -eq 'tests-skipped' -and
            $allSkipped.tests.skipped -eq 5 -and $allSkipped.runtime.startup -eq 'failure')
        multifilePositive = ($multiPositive.status -eq 'success' -and
            $multiPositive.sources.Count -eq 3 -and $multiPositive.plan.planId -eq ('g1-' + ('a' * 64)))
        multifileMissing = ($multiMissing.status -eq 'failure' -and
            $multiMissing.failureReason -eq 'manifest-source-missing' -and -not $multiMissing.maven.attempted)
        multifileExtra = ($multiExtra.status -eq 'failure' -and
            $multiExtra.failureReason -eq 'manifest-source-unexpected' -and -not $multiExtra.maven.attempted)
        multifileDuplicate = ($multiDuplicate.status -eq 'failure' -and
            $multiDuplicate.failureReason -eq 'manifest-duplicate' -and -not $multiDuplicate.maven.attempted)
        multifileChanged = ($multiChanged.status -eq 'failure' -and
            $multiChanged.failureReason -eq 'manifest-mismatch' -and -not $multiChanged.maven.attempted)
        multifileBrokenCompile = ($multiBroken.status -ne 'success' -and $multiBroken.maven.attempted -and
            $multiBroken.maven.exitCode -ne 0)
    }
    $failed = @($checks.Keys | Where-Object { -not $checks[$_] })
    $summary = [ordered]@{
        kind = 'g1-c-selftest-v2'
        mode = 'selftest'
        status = $(if ($failed.Count -eq 0) { 'passed' } else { 'failed' })
        checks = $checks
        failedChecks = $failed
        runs = [ordered]@{
            positive = (Join-Path $root 'positive')
            documentPositive = (Join-Path $root 'positive-document')
            brokenCompile = (Join-Path $root 'broken-compile')
            hashRejection = (Join-Path $root 'hash-rejection')
            noTests = (Join-Path $root 'no-tests')
            allSkipped = (Join-Path $root 'all-skipped')
            multifilePositive = (Join-Path $root 'multifile-positive')
            multifileMissing = (Join-Path $root 'multifile-missing-run')
            multifileExtra = (Join-Path $root 'multifile-extra-run')
            multifileDuplicate = (Join-Path $root 'multifile-duplicate-run')
            multifileChanged = (Join-Path $root 'multifile-changed-run')
            multifileBrokenCompile = (Join-Path $root 'multifile-broken-run')
        }
        timestamp = (Get-Date).ToString('o')
    }
    Write-Utf8Text (Join-Path $root 'selftest-summary.json') (($summary | ConvertTo-Json -Depth 8) + "`n")
    Write-Host ('HARNESS SELFTEST: ' + $summary.status)
    Write-Host ('Checks: ' + (($checks.Keys | ForEach-Object { $_ + '=' + $checks[$_] }) -join ', '))
    Write-Host ('Output: ' + $root)
    if ($failed.Count -ne 0) { exit 1 }
    exit 0
}

if (-not (Test-Path -LiteralPath $JavaHome)) { throw ('JavaHome not found: ' + $JavaHome) }
if (-not (Test-Path -LiteralPath $Maven)) { throw ('Maven not found: ' + $Maven) }
if (-not (Test-Path -LiteralPath $LocalRepository)) {
    throw ('LocalRepository not found: ' + $LocalRepository)
}
$OutputDirectory = Resolve-RepoPath $OutputDirectory
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$IsolationRoot = [IO.Path]::GetFullPath($IsolationRoot)
$LocalRepository = [IO.Path]::GetFullPath($LocalRepository)
$JavaHome = [IO.Path]::GetFullPath($JavaHome)

if ($SelfTest) {
    Invoke-SelfTest
}

if ([string]::IsNullOrWhiteSpace($SpecFile)) { throw 'SpecFile is required outside -SelfTest' }
if ([string]::IsNullOrWhiteSpace($SourceDirectory)) {
    if ([string]::IsNullOrWhiteSpace($SourceFile)) { throw 'SourceFile is required outside -SelfTest' }
    if ([string]::IsNullOrWhiteSpace($RelativePath)) { throw 'RelativePath is required outside -SelfTest' }
} elseif ([string]::IsNullOrWhiteSpace($ManifestFile)) {
    throw 'ManifestFile is required with SourceDirectory outside -SelfTest'
}
if ($Mode -eq 'generated-acceptance' -and [string]::IsNullOrWhiteSpace($PlanReceipt) -and
        [string]::IsNullOrWhiteSpace($ManifestFile)) {
    throw 'ManifestFile or PlanReceipt is required outside -SelfTest'
}

$SourceDirectory = Resolve-RepoPath $SourceDirectory
$SourceFile = Resolve-RepoPath $SourceFile
$SpecFile = Resolve-RepoPath $SpecFile
if (-not [string]::IsNullOrWhiteSpace($PlanReceipt)) { $PlanReceipt = Resolve-RepoPath $PlanReceipt }
if (-not [string]::IsNullOrWhiteSpace($ManifestFile)) { $ManifestFile = Resolve-RepoPath $ManifestFile }
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$runName = if ([string]::IsNullOrWhiteSpace($RunId)) {
    'generated-' + $stamp
} else {
    $RunId
}
$runDirectory = Join-Path $OutputDirectory $runName
$result = Invoke-Acceptance -SourceFile $SourceFile -SourceDirectory $SourceDirectory `
    -RelativePath $RelativePath -ExpectedSha256 $ExpectedSha256 -SpecFile $SpecFile `
    -Scenario $Scenario -PlanReceipt $PlanReceipt -ManifestFile $ManifestFile -Mode $Mode `
    -RunDirectory $runDirectory -KeepIsolation:$KeepIsolation
Write-Host ('G1.2 ' + $result.status + ': ' + $result.failureReason)
Write-Host ('Output: ' + $runDirectory)
if ($result.status -ne 'success') { exit 1 }
exit 0
