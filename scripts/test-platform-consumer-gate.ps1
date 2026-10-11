[CmdletBinding()]
param(
    [string]$LocalRepository = $env:CONSUMER_MAVEN_REPOSITORY,
    [string]$LocalConfig = $env:CONSUMER_GATE_CONFIG,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$MavenCommand,
    [string]$PostgresBin = $env:CONSUMER_POSTGRES_BIN,
    [string]$NodeCommand = 'node.exe',
    [string]$OutputDirectory,
    [switch]$Offline
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$root = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $root ('outputs/consumer-gate/' + [guid]::NewGuid().ToString('N')) }
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $OutputDirectory) { throw 'Gate output directory must be new' }
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$summary = [ordered]@{schemaVersion=1; status='running'; stage='preflight'; startedUtc=[datetime]::UtcNow.ToString('o');
    required=$true; producerModuleTestsExecuted=$false; diagnostics=$OutputDirectory; stages=@()}
$failure = $null
$repositoryLock = $null
$pgRun = Join-Path $OutputDirectory 'postgresql'
$h2Run = Join-Path $OutputDirectory 'bootstrap'
$savedPassword = $env:CONSUMER_DEMO_PASSWORD
function Save-Summary { $summary | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $OutputDirectory 'summary.json') -Encoding utf8 }
function Passed([string]$Name) { $summary.stages += $Name; Save-Summary }
function Input-Hashes {
    $files = @(Get-Item (Join-Path $root 'scripts/local-dependencies.json'))
    $files += @(Get-ChildItem $PSScriptRoot -File | Where-Object Name -Match '^(test-platform-consumer-|platform-consumer-)')
    if (Test-Path (Join-Path $root '.github/workflows/platform-consumer.yml')) { $files += Get-Item (Join-Path $root '.github/workflows/platform-consumer.yml') }
    foreach ($directory in @($producerDirectories) + @(Join-Path $root 'examples/platform-consumer')) {
        $files += Get-Item (Join-Path $directory 'pom.xml')
        if (Test-Path (Join-Path $directory 'src')) { $files += Get-ChildItem (Join-Path $directory 'src') -Recurse -File }
    }
    return @($files | Sort-Object FullName -Unique | ForEach-Object {
        [ordered]@{path=[IO.Path]::GetRelativePath($root,$_.FullName).Replace('\','/');sha256=(Get-FileHash $_.FullName).Hash}
    })
}
function Browser([string]$Phase) {
    & $NodeCommand (Join-Path $PSScriptRoot 'platform-consumer-browser.cjs') $pgRun $Phase *> `
        (Join-Path $OutputDirectory "browser-$Phase.log")
    if ($LASTEXITCODE -ne 0) { throw "Browser $Phase failed; inspect browser-$Phase.log" }
    $receipt = Get-Content (Join-Path $pgRun "browser-$Phase.json") -Raw | ConvertFrom-Json
    $expected = if ($Phase -eq 'first') { 8 } else { 3 }
    if ($receipt.status -ne 'passed' -or $receipt.cases.Count -ne $expected -or $receipt.pageErrors.Count -ne 0) {
        throw "Incomplete browser $Phase receipt"
    }
}
try {
    Save-Summary
    if ($env:GITHUB_ACTIONS -eq 'true' -and (-not $LocalRepository -or -not $LocalConfig)) {
        throw 'CI requires explicit CONSUMER_MAVEN_REPOSITORY and CONSUMER_GATE_CONFIG'
    }
    if (-not $LocalRepository) { $LocalRepository = Join-Path $env:USERPROFILE '.m2/repository' }
    $LocalRepository = (Resolve-Path -LiteralPath $LocalRepository).Path
    if (-not $LocalConfig) { $LocalConfig = Join-Path $root 'config/application-local.properties' }
    if (-not $PostgresBin) { $PostgresBin = 'C:/Program Files/PostgreSQL/17/bin' }
    if (-not $MavenCommand) { $MavenCommand = (Get-Command mvn.cmd -ErrorAction Stop).Source }
    $MavenCommand = (Resolve-Path -LiteralPath $MavenCommand).Path
    if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin/java.exe'))) { throw 'Set JavaHome/JAVA_HOME to JDK 21' }
    if (-not (Test-Path (Join-Path $PostgresBin 'psql.exe'))) { throw 'PostgreSQL psql.exe not found' }
    $NodeCommand = (Get-Command $NodeCommand -ErrorAction Stop).Source
    $javaVersion = (& (Join-Path $JavaHome 'bin/java.exe') -version 2>&1 | Out-String).Trim()
    if ($javaVersion -notmatch 'version "21\.') { throw 'JDK 21 required' }
    $summary.javaVersion=$javaVersion; $summary.localRepository=$LocalRepository
    $summary.nodeVersion=(& $NodeCommand --version | Out-String).Trim()
    # Resolve browser/tools before spending time on builds or creating owned databases.
    & $NodeCommand -e 'const {chromium}=require("playwright");if(require("playwright/package.json").version!=="1.62.1")throw Error("Playwright 1.62.1 required");(async()=>{const b=await chromium.launch({headless:true,channel:process.env.CONSUMER_BROWSER_CHANNEL||"chrome"});console.log(b.version());await b.close()})().catch(e=>{console.error(e);process.exitCode=1})' *> `
        (Join-Path $OutputDirectory 'browser-preflight.log')
    if ($LASTEXITCODE -ne 0) { throw 'Browser preflight failed: Node, Playwright 1.62.1 and Chrome/Edge are required' }
    $settings=@{}
    Get-Content -LiteralPath $LocalConfig | ForEach-Object { if ($_ -match '^([^#=]+)=(.*)$') {$settings[$matches[1].Trim()]=$matches[2].Trim()} }
    if ($settings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') { throw 'Expected explicitly configured local PostgreSQL' }
    $dbHost=$matches[1]; $dbPort=$matches[2]
    $oldPgPassword=$env:PGPASSWORD; $oldPgTimeout=$env:PGCONNECT_TIMEOUT
    try {
        $env:PGPASSWORD=$settings['spring.datasource.password']; $env:PGCONNECT_TIMEOUT='5'
        $pgVersion=& (Join-Path $PostgresBin 'psql.exe') -h $dbHost -p $dbPort -U $settings['spring.datasource.username'] -d postgres -X -v ON_ERROR_STOP=1 -tAc 'select version()' 2>&1
        if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL preflight failed: check local config, server and credentials' }
        $summary.postgresVersion=($pgVersion | Out-String).Trim()
    } finally { $env:PGPASSWORD=$oldPgPassword; $env:PGCONNECT_TIMEOUT=$oldPgTimeout }
    if (-not $env:CONSUMER_DEMO_PASSWORD) { $env:CONSUMER_DEMO_PASSWORD=[guid]::NewGuid().ToString('N') }
    $manifest=Get-Content (Join-Path $PSScriptRoot 'local-dependencies.json') -Raw | ConvertFrom-Json
    $needed=@{}
    function Need([string]$Id) {
        if ($needed.ContainsKey($Id)) { return }
        $entry=@($manifest.projects | Where-Object id -eq $Id)
        if ($entry.Count -ne 1) { throw "Unknown producer project: $Id" }
        $needed[$Id]=$true
        foreach ($dependency in $entry[0].dependsOn) { Need $dependency }
    }
    Need 'platform-vaadin-starter'
    $producerDirectories=@($manifest.projects | Where-Object {$needed.ContainsKey($_.id) -and $_.relativePath.StartsWith('platform-')} |
        ForEach-Object { (Resolve-Path (Join-Path $root $_.relativePath)).Path })
    $inputs=Input-Hashes
    $inputs | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutputDirectory 'input-hashes.json') -Encoding utf8
    $summary.producerModules=@($producerDirectories | ForEach-Object { Split-Path -Leaf $_ })
    $summary.gitHead=(& git -C $root rev-parse HEAD | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify checked out Git revision' }
    Passed 'preflight'
    # Keep concurrent gates from replacing SNAPSHOT artifacts between builds.
    $repositoryLock = [IO.File]::Open((Join-Path $LocalRepository '.consumer-gate.lock'),
        [IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $summary.stage='producer-and-h2'; Save-Summary
    $bootstrap=@{LocalRepository=$LocalRepository;JavaHome=$JavaHome;MavenCommand=$MavenCommand;
        EvidenceDirectory=$h2Run;PrepareArtifacts=$true;ProbePackage=$true;Offline=$Offline.IsPresent}
    & (Join-Path $PSScriptRoot 'test-platform-consumer-bootstrap.ps1') @bootstrap *> `
        (Join-Path $OutputDirectory 'bootstrap-console.log')
    $bootstrapReceipt=Get-Content (Join-Path $h2Run 'summary.json') -Raw | ConvertFrom-Json
    if (-not $bootstrapReceipt.preparedPlatformSources -or $bootstrapReceipt.packageProbes.Count -ne 2) { throw 'Incomplete producer/package receipt' }
    . (Join-Path $PSScriptRoot 'platform-consumer-gate-lib.ps1')
    $h2Cases=@(Assert-ConsumerJUnit (Join-Path $h2Run 'surefire-reports'))
    Passed 'producer-clean-install-current-sources'
    Passed 'h2-23-and-packaged-startup-and-missing-scan-rejection'
    $summary.stage='postgresql'; Save-Summary
    $pg=@{LocalRepository=$LocalRepository;LocalConfig=$LocalConfig;PostgresBin=$PostgresBin;
        JavaHome=$JavaHome;MavenCommand=$MavenCommand;Offline=$Offline.IsPresent}
    & (Join-Path $PSScriptRoot 'test-platform-consumer-postgresql.ps1') @pg -EvidenceDirectory $pgRun *> `
        (Join-Path $OutputDirectory 'postgresql-console.log')
    $pgCases=@(Assert-ConsumerJUnit (Join-Path $pgRun 'surefire-reports'))
    $package=Get-Content (Join-Path $pgRun 'package-provenance.json') -Raw | ConvertFrom-Json
    if ($package.status -ne 'passed') { throw 'Package provenance not passed' }
    Passed 'postgresql-23-and-production-package'
    $summary.stage='browser-first'; Save-Summary; Browser 'first'; Passed 'browser-first'
    $summary.stage='restart'; Save-Summary
    & (Join-Path $PSScriptRoot 'test-platform-consumer-postgresql.ps1') @pg -Action Restart -RunDirectory $pgRun *> `
        (Join-Path $OutputDirectory 'restart-console.log')
    Passed 'restart-validate-and-identical-business-state'
    $summary.stage='browser-restarted'; Save-Summary; Browser 'restarted'; Passed 'browser-restarted'
    $summary.stage='source-provenance'; Save-Summary
    $builtArtifacts=@()
    foreach ($library in $package.platformLibraries) {
        $producer=@($producerDirectories | Where-Object { (Split-Path -Leaf $_) -eq $library.artifact })
        if ($producer.Count -eq 1) {
            $builtJar=Join-Path $producer[0] "target/$($library.artifact)-1.0-SNAPSHOT.jar"
            if ((Get-FileHash $builtJar).Hash -ne $library.sha256) { throw "Consumer did not receive the current build of $($library.artifact)" }
            $builtArtifacts += [ordered]@{artifact=$library.artifact;sha256=$library.sha256;sourceModule=[IO.Path]::GetRelativePath($root,$producer[0])}
        }
    }
    if ($builtArtifacts.Count -ne ($producerDirectories.Count - 1)) { throw 'Incomplete producer JAR provenance (only BOM may have no JAR)' }
    $builtArtifacts | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutputDirectory 'producer-artifacts.json') -Encoding utf8
    foreach ($artifact in $bootstrapReceipt.consumedArtifacts) {
        $coordinate=$artifact.coordinates.Split(':')
        $repositoryFile=Join-Path $LocalRepository ($coordinate[0].Replace('.','/') + '/' + $coordinate[1] + '/' + $coordinate[2] + '/' + $coordinate[1] + '-' + $coordinate[2] + '.' + $coordinate[3])
        if ((Get-FileHash $repositoryFile).Hash -ne $artifact.sha256) { throw "Consumed repository artifact changed during gate: $($artifact.coordinates)" }
    }
    if (($inputs | ConvertTo-Json -Depth 4 -Compress) -cne ((Input-Hashes) | ConvertTo-Json -Depth 4 -Compress)) { throw 'Gate inputs changed during run; repeat on a stable tree' }
    $summary.h2Tests=$h2Cases.Count; $summary.postgresTests=$pgCases.Count
    $summary.jarSha256=$package.jarSha256
    Passed 'unchanged-build-inputs'
} catch { $failure=$_.Exception.Message; $summary.error=$failure; $summary.failedStage=$summary.stage }
finally {
    $summary.stage='cleanup'; Save-Summary
    if (Test-Path (Join-Path $pgRun 'state.json')) {
        try {
            & (Join-Path $PSScriptRoot 'test-platform-consumer-postgresql.ps1') -Action Cleanup -RunDirectory $pgRun `
                -LocalConfig $LocalConfig -PostgresBin $PostgresBin -JavaHome $JavaHome *> `
                (Join-Path $OutputDirectory 'cleanup-console.log')
            Passed 'owned-process-and-databases-cleaned'
        } catch {
            $summary.cleanupError=$_.Exception.Message
            if (-not $failure) { $failure='Owned resource cleanup failed' }
        }
    }
    $env:CONSUMER_DEMO_PASSWORD=$savedPassword
    if ($repositoryLock) { $repositoryLock.Dispose() }
    $summary.finishedUtc=[datetime]::UtcNow.ToString('o')
    $summary.status=if ($failure) {'failed'} else {'passed'}
    Save-Summary
}
if ($failure) { throw "Consumer gate failed: $failure. Diagnostics: $OutputDirectory" }
Write-Host "PASS: required consumer gate; diagnostics: $OutputDirectory"

