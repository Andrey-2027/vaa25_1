[CmdletBinding()]
param(
    [string]$MavenCommand,
    [string]$JavaHome,
    [string]$LocalRepository,
    [string]$EvidenceDirectory,
    [switch]$PrepareArtifacts,
    [switch]$ProbePackage,
    [switch]$Offline
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$consumerSource = Join-Path $projectRoot 'examples/platform-consumer'
$runId = [guid]::NewGuid().ToString('N')
$evidence = Join-Path $consumerSource "target/bootstrap-evidence/$runId"
if ($EvidenceDirectory) { $evidence = [IO.Path]::GetFullPath($EvidenceDirectory) }
if (Test-Path -LiteralPath $evidence) { throw 'Evidence directory must be new for this run' }
$isolated = Join-Path ([IO.Path]::GetTempPath()) "ipro-platform-consumer-$runId"

if (-not $LocalRepository) {
    $LocalRepository = Join-Path $projectRoot '.local-maven-repository'
}
$LocalRepository = [IO.Path]::GetFullPath($LocalRepository)
if (-not $MavenCommand) {
    $mavenOnPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($mavenOnPath) {
        $MavenCommand = $mavenOnPath.Source
    } elseif ($env:IDEA_MAVEN_HOME) {
        $MavenCommand = Join-Path $env:IDEA_MAVEN_HOME 'bin/mvn.cmd'
    } else {
        throw 'Pass -MavenCommand or set IDEA_MAVEN_HOME; Maven was not found on PATH.'
    }
}
$MavenCommand = (Resolve-Path -LiteralPath $MavenCommand).Path
if (-not $JavaHome) {
    $JavaHome = $env:JAVA_HOME
    if (-not $JavaHome) {
        $JavaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java.exe).Source)
    }
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) {
    throw "Java executable not found under $JavaHome"
}

New-Item -ItemType Directory -Path $evidence -Force | Out-Null
New-Item -ItemType Directory -Path $isolated | Out-Null
$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
$commonArguments = @("-Dmaven.repo.local=$LocalRepository")
if ($Offline) { $commonArguments += '-o' }

function Invoke-ConsumerMaven([string]$Pom, [string[]]$Arguments, [string]$Log) {
    $resolvedPom = (Resolve-Path -LiteralPath $Pom).Path
    Write-Host "Maven: $resolvedPom"
    & $MavenCommand @commonArguments -f $resolvedPom @Arguments 2>&1 |
        Tee-Object -FilePath $Log | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Get-Content -LiteralPath $Log -Tail 25 | Out-Host
        throw "Maven failed with exit code $LASTEXITCODE; see $Log"
    }
}

function Invoke-PackagedBootstrap([string]$Jar, [switch]$MissingScanProperty) {
    $probeName = if ($MissingScanProperty) { 'missing-scan' } else { 'packaged-bootstrap' }
    $stdout = Join-Path $evidence "$probeName.stdout.log"
    $stderr = Join-Path $evidence "$probeName.stderr.log"
    $arguments = @('-jar', ('"' + $Jar + '"'), '--spring.profiles.active=bootstrap-smoke',
        '--server.port=0', '--vaadin.productionMode=true', '--spring.main.banner-mode=off')
    if ($MissingScanProperty) { $arguments += '--platform.subsystem-scan-package=' }
    $previousPassword = $env:CONSUMER_DEMO_PASSWORD
    $env:CONSUMER_DEMO_PASSWORD = 'isolated-bootstrap-probe'
    $process = $null
    try {
        $process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList $arguments `
            -WorkingDirectory (Split-Path -Parent $Jar) -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $deadline = [DateTime]::UtcNow.AddSeconds(60)
        do {
            Start-Sleep -Milliseconds 100
            $text = if (Test-Path -LiteralPath $stdout) { Get-Content -LiteralPath $stdout -Raw } else { '' }
            if (-not $MissingScanProperty -and $text -match 'Started ConsumerApplication') {
                if ($text -notmatch 'Tomcat started on port (\d+)') {
                    throw "Cannot identify the test server port in $stdout"
                }
                $port = [int]$Matches[1]
                $loginResponse = Invoke-WebRequest -Uri "http://127.0.0.1:$port/login" -UseBasicParsing -TimeoutSec 15
                if ($loginResponse.StatusCode -ne 200 -or $loginResponse.Content -notmatch 'VAADIN') {
                    throw 'Packaged login did not return the Vaadin bootstrap page'
                }
                return $true
            }
            if ($process.HasExited) { break }
        } while ([DateTime]::UtcNow -lt $deadline)
        $errorText = if (Test-Path -LiteralPath $stderr) { Get-Content -LiteralPath $stderr -Raw } else { '' }
        if ($MissingScanProperty -and $process.HasExited -and $process.ExitCode -ne 0 -and
                (($text + $errorText) -match "Property 'platform.subsystem-scan-package' is required")) {
            return $true
        }
        throw "Packaged probe '$probeName' did not reach its expected result; see $stdout and $stderr"
    } finally {
        if ($process -and -not $process.HasExited) { $process.Kill(); $process.WaitForExit(5000) | Out-Null }
        if ($process) { $process.Dispose() }
        $env:CONSUMER_DEMO_PASSWORD = $previousPassword
    }
}

try {
    if ($PrepareArtifacts) {
        $manifest = Get-Content -LiteralPath (Join-Path $projectRoot 'scripts/local-dependencies.json') -Raw |
            ConvertFrom-Json
        $needed = @{}
        function Add-ProducerProject([string]$Id) {
            if ($needed.ContainsKey($Id)) { return }
            $entry = @($manifest.projects | Where-Object id -eq $Id)
            if ($entry.Count -ne 1) { throw "Producer project '$Id' not uniquely defined" }
            $needed[$Id] = $true
            foreach ($dependency in $entry[0].dependsOn) { Add-ProducerProject $dependency }
        }
        Add-ProducerProject 'platform-vaadin-starter'
        $modules = @($manifest.projects | Where-Object {
            $needed.ContainsKey($_.id) -and $_.relativePath.StartsWith('platform-')
        } | ForEach-Object {
            $modulePom = Join-Path $projectRoot ($_.relativePath + '/pom.xml')
            if (-not (Test-Path -LiteralPath $modulePom)) { throw "Missing producer POM: $modulePom" }
            $moduleDirectory = Split-Path -Parent (Resolve-Path -LiteralPath $modulePom).Path
            $baseUri = [Uri]::new($evidence.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar)
            $moduleUri = [Uri]::new($moduleDirectory.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar)
            $relativeModule = [Uri]::UnescapeDataString($baseUri.MakeRelativeUri($moduleUri).ToString())
            '<module>' + [Security.SecurityElement]::Escape($relativeModule) + '</module>'
        })
        $producerPom = Join-Path $evidence 'producer-pom.xml'
        $producerXml = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>' +
            '<groupId>com.example.build</groupId><artifactId>consumer-producer-check</artifactId>' +
            '<version>1</version><packaging>pom</packaging><modules>' + ($modules -join '') + '</modules></project>'
        [IO.File]::WriteAllText($producerPom, $producerXml, [Text.UTF8Encoding]::new($false))
        # Producer compilation only; the consumer acceptance tests below are mandatory.
        # Non-platform local dependencies must already be prepared by the normal bootstrap.
        Invoke-ConsumerMaven $producerPom @('-Dmaven.test.skip=true', 'clean', 'install') (Join-Path $evidence 'producer.log')
    }

    $consumerCopy = Join-Path $isolated 'consumer'
    New-Item -ItemType Directory -Path $consumerCopy | Out-Null
    Copy-Item -LiteralPath (Join-Path $consumerSource 'pom.xml') -Destination $consumerCopy
    Copy-Item -LiteralPath (Join-Path $consumerSource 'src') -Destination $consumerCopy -Recurse
    $consumerPom = (Resolve-Path -LiteralPath (Join-Path $consumerCopy 'pom.xml')).Path
    Push-Location -LiteralPath $consumerCopy
    try {
        $consumerProfiles = if ($ProbePackage) { '-Pbootstrap-smoke,production' } else { '-Pbootstrap-smoke' }
        Invoke-ConsumerMaven $consumerPom @($consumerProfiles, 'clean', 'verify') (Join-Path $evidence 'build.log')
    } finally {
        Pop-Location
    }

    . (Join-Path $PSScriptRoot 'platform-consumer-gate-lib.ps1')
    $cases = @(Assert-ConsumerJUnit (Join-Path $consumerCopy 'target/surefire-reports'))

    $packageProbes = @()
    if ($ProbePackage) {
        $jar = (Resolve-Path -LiteralPath (Join-Path $consumerCopy 'target/platform-consumer-exec.jar')).Path
        [void](Invoke-PackagedBootstrap $jar)
        $packageProbes += 'production web packaged bootstrap and HTTP login'
        [void](Invoke-PackagedBootstrap $jar -MissingScanProperty)
        $packageProbes += 'missing subsystem scan property fails startup'
    }

    $treePath = Join-Path $consumerCopy 'target/consumer-runtime-dependency-tree.json'
    $tree = Get-Content -LiteralPath $treePath -Raw | ConvertFrom-Json
    $artifactHashes = [Collections.Generic.List[object]]::new()
    function Add-ConsumedArtifacts($Node) {
        if ($Node.groupId.StartsWith('org.ipro')) {
            $artifactDirectory = Join-Path $LocalRepository ($Node.groupId.Replace('.', '/') + '/' +
                $Node.artifactId + '/' + $Node.version)
            foreach ($extension in @('jar', 'pom')) {
                $artifact = Join-Path $artifactDirectory ($Node.artifactId + '-' + $Node.version + '.' + $extension)
                if (Test-Path -LiteralPath $artifact) {
                    $artifactHashes.Add([pscustomobject]@{
                        coordinates = "$($Node.groupId):$($Node.artifactId):$($Node.version):$extension"
                        sha256 = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash
                    })
                }
            }
        }
        if ($Node.PSObject.Properties['children']) {
            foreach ($child in $Node.children) { Add-ConsumedArtifacts $child }
        }
    }
    Add-ConsumedArtifacts $tree
    $previousDiagnosticPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $javaVersion = (& (Join-Path $JavaHome 'bin/java.exe') -version 2>&1 | Out-String).Trim()
    } finally { $ErrorActionPreference = $previousDiagnosticPreference }
    $summary = [ordered]@{
        runId = $runId
        stage = 'P3 aggregate save, lifecycle, numbering and server-side Vaadin forms; H2; PostgreSQL and browser acceptance pending P4'
        consumerDirectory = $consumerCopy
        localRepository = $LocalRepository
        consumerPomSha256 = (Get-FileHash -LiteralPath $consumerPom -Algorithm SHA256).Hash
        javaVersion = $javaVersion
        preparedPlatformSources = $PrepareArtifacts.IsPresent
        producerModuleTestsExecuted = $false
        tests = $cases
        packageProbes = $packageProbes
        consumedArtifacts = $artifactHashes
        consumerSources = @(Get-ChildItem -LiteralPath (Join-Path $consumerCopy 'src') -Recurse -File |
            ForEach-Object { [pscustomobject]@{
                path = [IO.Path]::GetRelativePath($consumerCopy, $_.FullName)
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            } })
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $evidence 'summary.json') -Encoding UTF8
    Copy-Item -LiteralPath $treePath -Destination $evidence
    Copy-Item -LiteralPath (Join-Path $consumerCopy 'target/platform-consumer-exec.jar') -Destination $evidence
    Write-Host "PASS: $($cases.Count) consumer checks; evidence: $evidence"
} finally {
    if (Test-Path -LiteralPath (Join-Path $isolated 'consumer/target/surefire-reports')) {
        Copy-Item -LiteralPath (Join-Path $isolated 'consumer/target/surefire-reports') -Destination $evidence -Recurse -Force
    }
    foreach ($artifactName in @('platform-consumer-exec.jar', 'consumer-runtime-dependency-tree.json')) {
        $artifactPath = Join-Path $isolated ('consumer/target/' + $artifactName)
        if (Test-Path -LiteralPath $artifactPath) {
            Copy-Item -LiteralPath $artifactPath -Destination $evidence -Force
        }
    }
    $env:JAVA_HOME = $previousJavaHome
}

