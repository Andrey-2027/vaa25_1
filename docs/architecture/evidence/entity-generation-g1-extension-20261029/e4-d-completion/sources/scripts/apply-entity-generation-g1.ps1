[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [switch]$Apply,
    [string]$ProjectRoot,
    [string]$SourceRoot = 'src/main/java',
    [string]$CSummary,
    [string]$ReviewedPlan,
    [string]$Dependencies,
    [string]$DependencyTree,
    [string]$RunId,
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = (Join-Path $env:USERPROFILE '.m2\repository'),
    [string]$JacksonVersion = '2.18.4',
    [switch]$SkipCoreBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:SupportRoot = Join-Path $PSScriptRoot 'entity-generation-g1-apply'
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $script:RepoRoot 'outputs\entity-generation-g1\g1-3'
}
$runName = if ([string]::IsNullOrWhiteSpace($RunId)) {
    if ($Apply) { 'apply-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
    else { 'apply-selftest-' + (Get-Date -Format 'yyyyMMdd-HHmmss') }
} else { $RunId }
if ($runName -notmatch '^[A-Za-z0-9_-]+$') { throw 'RunId must be a single portable directory name.' }
$runDirectory = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) $runName
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$script:LauncherLog = Join-Path $runDirectory 'launcher.log'
$script:StatusFile = Join-Path $runDirectory 'status.txt'

function Write-LauncherLog {
    param([string]$Text)
    $line = ('[' + (Get-Date -Format 'HH:mm:ss') + '] ' + $Text)
    Write-Host $line
    Add-Content -LiteralPath $script:LauncherLog -Value $line -Encoding UTF8
}

function Set-Status {
    param([string]$Value)
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($script:StatusFile, $Value + "`n", $encoding)
}

function Stop-Launcher {
    param([string]$Reason)
    Set-Status ('failed: ' + $Reason)
    Write-LauncherLog ('FAILED: ' + $Reason)
    exit 1
}

try {
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
        Stop-Launcher ('JDK not found: ' + $JavaHome)
    }
    if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
        Stop-Launcher ('Java launcher not found: ' + $JavaHome)
    }
    $env:JAVA_HOME = $JavaHome
    $env:PATH = (Join-Path $JavaHome 'bin') + ';' + $env:PATH

    $core = Join-Path $script:RepoRoot 'entity-design-core'
    $coreClasses = Join-Path $core 'target\classes'
    if (-not $SkipCoreBuild) {
        if (-not (Test-Path -LiteralPath $Maven)) {
            Stop-Launcher ('Maven not found: ' + $Maven)
        }
        $coreLog = Join-Path $runDirectory 'core-build.log'
        Write-LauncherLog ('Building entity-design-core: ' + $core)
        & $Maven -o -B -f (Join-Path $core 'pom.xml') test-compile 2>&1 |
            Tee-Object -FilePath $coreLog
        if ($LASTEXITCODE -ne 0) {
            Stop-Launcher ('entity-design-core test-compile failed; see ' + $coreLog)
        }
    }
    if (-not (Test-Path -LiteralPath (Join-Path $coreClasses 'org\ipro\design\generation\CatalogJavaGenerator.class'))) {
        Stop-Launcher ('entity-design-core classes are missing: ' + $coreClasses)
    }

    $jacksonRoot = Join-Path $LocalRepository ('com\fasterxml\jackson\core')
    $jacksonJars = @(
        (Join-Path $jacksonRoot ('jackson-databind\' + $JacksonVersion + '\jackson-databind-' + $JacksonVersion + '.jar')),
        (Join-Path $jacksonRoot ('jackson-core\' + $JacksonVersion + '\jackson-core-' + $JacksonVersion + '.jar')),
        (Join-Path $jacksonRoot ('jackson-annotations\' + $JacksonVersion + '\jackson-annotations-' + $JacksonVersion + '.jar'))
    )
    foreach ($jar in $jacksonJars) {
        if (-not (Test-Path -LiteralPath $jar)) {
            Stop-Launcher ('Jackson jar not found: ' + $jar)
        }
    }
    $jacksonClasspath = ($jacksonJars -join ';')

    $classes = Join-Path $runDirectory 'classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $sources = @(Get-ChildItem -LiteralPath (Join-Path $script:SupportRoot 'src\main\java') -Recurse -Filter '*.java' -File |
        ForEach-Object { $_.FullName })
    if ($sources.Count -eq 0) {
        Stop-Launcher ('No D support sources found under ' + $script:SupportRoot)
    }
    $compileLog = Join-Path $runDirectory 'javac.log'
    Write-LauncherLog ('Compiling ' + $sources.Count + ' D support source files')
    & (Join-Path $JavaHome 'bin\javac.exe') -encoding UTF-8 --release 21 `
        -cp ($classes + ';' + $coreClasses + ';' + $jacksonClasspath) `
        -d $classes @sources 2>&1 | Tee-Object -FilePath $compileLog
    if ($LASTEXITCODE -ne 0) {
        Stop-Launcher ('D support compilation failed; see ' + $compileLog)
    }

    $classpath = $classes + ';' + $coreClasses + ';' + $jacksonClasspath

    if ($Apply) {
        foreach ($required in @($ProjectRoot, $CSummary, $ReviewedPlan)) {
            if ([string]::IsNullOrWhiteSpace($required)) {
                Stop-Launcher 'Apply requires ProjectRoot, CSummary and ReviewedPlan'
            }
        }
        $ProjectRoot = [IO.Path]::GetFullPath($ProjectRoot)
        $CSummary = [IO.Path]::GetFullPath($CSummary)
        $ReviewedPlan = [IO.Path]::GetFullPath($ReviewedPlan)
        foreach ($file in @($ProjectRoot, $CSummary, $ReviewedPlan)) {
            if (-not (Test-Path -LiteralPath $file)) { Stop-Launcher ('Path not found: ' + $file) }
        }
        if ([string]::IsNullOrWhiteSpace($SourceRoot)) { Stop-Launcher 'SourceRoot is required' }

        if ([string]::IsNullOrWhiteSpace($Dependencies)) {
            if ([string]::IsNullOrWhiteSpace($DependencyTree)) {
                Stop-Launcher 'Apply requires Dependencies or DependencyTree'
            }
            $DependencyTree = [IO.Path]::GetFullPath($DependencyTree)
            if (-not (Test-Path -LiteralPath $DependencyTree)) {
                Stop-Launcher ('Dependency tree not found: ' + $DependencyTree)
            }
            $tree = Get-Content -LiteralPath $DependencyTree -Raw | ConvertFrom-Json
            $seen = @{}
            $items = New-Object System.Collections.Generic.List[object]
            $queue = New-Object System.Collections.Queue
            $queue.Enqueue($tree)
            while ($queue.Count -gt 0) {
                $node = $queue.Dequeue()
                if ($null -eq $node) { continue }
                if (([string]$node.groupId -eq 'org.ipro') -and ([string]$node.type -eq 'jar')) {
                    $classifier = [string]$node.classifier
                    $file = [string]$node.artifactId + '-' + [string]$node.version +
                        $(if ([string]::IsNullOrWhiteSpace($classifier)) { '' } else { '-' + $classifier }) + '.jar'
                    $path = Join-Path $LocalRepository ('org\ipro\' + $node.artifactId + '\' +
                        $node.version + '\' + $file)
                    $id = [string]$node.groupId + ':' + [string]$node.artifactId + ':' +
                        [string]$node.type + ':' + $classifier + ':' + [string]$node.version
                    if (-not $seen.ContainsKey($id)) {
                        $seen[$id] = $true
                        $items.Add([ordered]@{ id = $id; path = $path }) | Out-Null
                    }
                }
                if (@($node.PSObject.Properties.Name) -contains 'children') {
                    foreach ($child in @($node.children)) { if ($null -ne $child) { $queue.Enqueue($child) } }
                }
            }
            $Dependencies = Join-Path $runDirectory 'current-dependencies.json'
            $sorted = @($items | Sort-Object { $_.id })
            [IO.File]::WriteAllText($Dependencies, (($sorted | ConvertTo-Json -Depth 5) + "`n"),
                (New-Object System.Text.UTF8Encoding($false)))
        } else {
            $Dependencies = [IO.Path]::GetFullPath($Dependencies)
            if (-not (Test-Path -LiteralPath $Dependencies)) {
                Stop-Launcher ('Dependencies file not found: ' + $Dependencies)
            }
        }

        $resultFile = Join-Path $runDirectory 'apply-result.json'
        $applyLog = Join-Path $runDirectory 'apply.log'
        Write-LauncherLog ('Applying reviewed plan to ' + $ProjectRoot + ' (' + $SourceRoot + ')')
        & (Join-Path $JavaHome 'bin\java.exe') -cp $classpath org.ipro.design.apply.ApplyHarness `
            --apply $ProjectRoot $SourceRoot $CSummary $ReviewedPlan $Dependencies $resultFile `
            2>&1 | Tee-Object -FilePath $applyLog
        $applyExit = $LASTEXITCODE
        if (-not (Test-Path -LiteralPath $resultFile)) {
            Stop-Launcher ('D apply did not write a result; see ' + $applyLog)
        }
        $result = Get-Content -LiteralPath $resultFile -Raw | ConvertFrom-Json
        Set-Status ([string]$result.status + '/' + [string]$result.code +
            ': ' + [string]$result.message)
        Write-LauncherLog ('D apply: ' + $result.status + '/' + $result.code)
        Write-LauncherLog ('Result: ' + $resultFile)
        exit $applyExit
    }

    $selfTestLog = Join-Path $runDirectory 'selftest.log'
    $arguments = @('-cp', $classpath, 'org.ipro.design.apply.ApplyHarness', '--self-test', $runDirectory)
    Write-LauncherLog 'Running D self-test matrix'
    & (Join-Path $JavaHome 'bin\java.exe') @arguments 2>&1 | Tee-Object -FilePath $selfTestLog
    $selfTestExit = $LASTEXITCODE
    $summaryPath = Join-Path $runDirectory 'selftest-summary.json'
    if (-not (Test-Path -LiteralPath $summaryPath)) {
        Stop-Launcher ('D self-test did not write a summary; see ' + $selfTestLog)
    }
    $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
    if ($selfTestExit -ne 0 -or $summary.status -ne 'success') {
        Set-Status ('failed: selftest exit ' + $selfTestExit + '; see ' + $summaryPath)
        Write-LauncherLog ('FAILED: D self-test exit ' + $selfTestExit)
        exit 1
    }
    Set-Status 'success'
    Write-LauncherLog ('D self-test success: ' + $summary.passed + ' passed, ' + $summary.skipped + ' skipped')
    Write-LauncherLog ('Evidence: ' + $summaryPath)
    exit 0
} catch {
    Set-Status ('failed: ' + $_.Exception.Message)
    Write-LauncherLog ('EXCEPTION: ' + $_.Exception.Message)
    exit 1
}
