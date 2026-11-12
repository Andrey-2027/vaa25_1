[CmdletBinding()]
param(
    [ValidateSet('Prepare','Test','Start','Stop','Status')][string]$Action = 'Test',
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository',
    # Reuse the already prepared, isolated navigation stand; no seeding or schema changes.
    [string]$PilotProperties = 'config/navigation-pilot.properties.local',
    [ValidateRange(1024,65535)][int]$Port = 8088
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pilotRoot = Split-Path $PSScriptRoot -Parent
$pilotOutput = Join-Path $pilotRoot 'outputs/entity-explorer-p0'
$pilotStateFile = Join-Path $pilotOutput 'server-state.json'
$pilotArgs = Join-Path $pilotOutput 'server.args'
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $pilotRoot
try {
    $env:JAVA_HOME = $JavaHome
    New-Item -ItemType Directory -Force -Path $pilotOutput | Out-Null
    if ($Action -in @('Stop','Status')) {
        $owned = $null
        if (Test-Path $pilotStateFile) {
            $state = Get-Content $pilotStateFile -Raw | ConvertFrom-Json
            $owned = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
            if ($owned -and ($owned.Path -ne $state.executable -or
                $owned.StartTime.ToUniversalTime().Ticks -ne $state.startTimeUtcTicks)) {
                throw 'Stored PID does not belong to this stand.'
            }
        }
        if ($Action -eq 'Stop' -and $owned) { Stop-Process -Id $owned.Id }
        Write-Output "Stand running: $([bool]$owned -and $Action -ne 'Stop')"
        return
    }
    if ($Action -eq 'Prepare') {
        & $Maven -o "-Dmaven.repo.local=$LocalRepository" dependency:build-classpath '-Dmdep.includeScope=test' `
            '-Dmdep.outputFile=outputs/entity-explorer-p0/classpath.txt' *> "$pilotOutput/dependencies.log"
        if ($LASTEXITCODE -ne 0) { throw 'Classpath resolution failed; see dependencies.log.' }
        $copies = @(); $index = 0
        foreach ($jar in ((Get-Content "$pilotOutput/classpath.txt" -Raw).Trim() -split ';')) {
            # javac ZIP close fails on some sandboxed Maven-cache paths; copy unchanged jars.
            $directory = Join-Path "$pilotOutput/deps" ($index++).ToString('D4')
            New-Item -ItemType Directory -Force -Path $directory | Out-Null
            $copy = Join-Path $directory (Split-Path $jar -Leaf)
            Copy-Item -LiteralPath $jar -Destination $copy -Force
            $copies += $copy
        }
        # Current local RLS classes supply AccessGrantRepository missing in the installed snapshot.
        if (!(Test-Path 'platform-rls/target/classes/org/ipro/rls/AccessGrantRepository.class')) {
            throw 'Build the local platform-rls module first.'
        }
        $classpath = 'platform-rls/target/classes;' + ($copies -join ';')
        [IO.File]::WriteAllText("$pilotOutput/local-classpath.txt", $classpath)
        New-Item -ItemType Directory -Force -Path "$pilotOutput/classes" | Out-Null
        $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
            '-d','outputs/entity-explorer-p0/classes')
        $arguments += Get-ChildItem src/main/java -Recurse -Filter '*.java' | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
        [IO.File]::WriteAllLines("$pilotOutput/compile.args", [string[]]$arguments, [Text.UTF8Encoding]::new($false))
        & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$pilotOutput/compile.args") *> "$pilotOutput/compile.log"
        if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; see compile.log.' }
        Write-Output 'P0 sources compiled. Next: -Action Test or -Action Start.'
        return
    }
    if (!(Test-Path "$pilotOutput/classes/org/ip/Application.class")) { throw 'Run -Action Prepare first.' }
    $classpath = 'outputs/entity-explorer-p0/classes;src/main/resources;' + (Get-Content "$pilotOutput/local-classpath.txt" -Raw).Trim()
    if ($Action -eq 'Test') {
        New-Item -ItemType Directory -Force -Path "$pilotOutput/test-classes","$pilotOutput/tmp" | Out-Null
        $engine = @(Get-ChildItem "$pilotOutput/deps" -Recurse -Filter 'junit-platform-engine-*.jar')
        if ($engine.Count -ne 1) { throw 'Expected one JUnit engine.' }
        $version = $engine[0].BaseName.Substring('junit-platform-engine-'.Length)
        $launcher = Join-Path $LocalRepository "org/junit/platform/junit-platform-launcher/$version/junit-platform-launcher-$version.jar"
        Copy-Item -LiteralPath $launcher -Destination "$pilotOutput/junit-launcher.jar" -Force
        $classpath += ';outputs/entity-explorer-p0/junit-launcher.jar'
        $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
            '-d','outputs/entity-explorer-p0/test-classes',
            'scripts/entity-explorer-pilot/ExplorerPilotChecks.java',
            'src/test/java/org/ip/views/admin/fixture/EntityExplorerDensityFixture.java',
            'src/test/java/org/ip/views/admin/EntityExplorerPilotBoundaryTest.java',
            'src/test/java/org/ip/views/admin/CardSections.java',
            'src/test/java/org/ip/views/EntityExplorerAddressWiringTest.java',
            'src/test/java/org/ip/views/EntityExplorerRouteGateTest.java')
        [IO.File]::WriteAllLines("$pilotOutput/test-compile.args", [string[]]$arguments, [Text.UTF8Encoding]::new($false))
        & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$pilotOutput/test-compile.args") *> "$pilotOutput/test-compile.log"
        if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed; see test-compile.log.' }
        $agent = @(Get-ChildItem "$pilotOutput/deps" -Recurse -Filter 'mockito-core-*.jar')
        if ($agent.Count -ne 1) { throw 'Expected one Mockito agent.' }
        $arguments = @(('-javaagent:'+ $agent[0].FullName),'-Dfile.encoding=UTF-8',("-Djava.io.tmpdir=$pilotOutput/tmp"),
            '-cp',('outputs/entity-explorer-p0/test-classes;'+$classpath),'ExplorerPilotChecks')
        & (Join-Path $JavaHome 'bin/java.exe') @arguments *> "$pilotOutput/test.log"
        Get-Content "$pilotOutput/test.log" -Tail 30
        if ($LASTEXITCODE -ne 0) { throw 'P0 checks failed; see test.log.' }
        return
    }
    $propertiesPath = (Resolve-Path -LiteralPath $PilotProperties).Path
    $urlLine = @(Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^spring.datasource.url=' })
    if ($urlLine.Count -ne 1 -or $urlLine[0] -notmatch '^spring.datasource.url=(jdbc:postgresql://(?:localhost|127\.0\.0\.1):\d+/(?:navigation_pilot|entity_explorer_pilot)_[a-f0-9]{32})$') {
        throw 'Only a prepared isolated local pilot database is allowed.'
    }
    $url = $Matches[1]
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
    try { $listener.Start() } finally { $listener.Stop() }
    New-Item -ItemType Directory -Force -Path "$pilotOutput/home" | Out-Null
    $arguments = @('-Dfile.encoding=UTF-8',('"-Duser.home='+$pilotOutput.Replace('\','/')+'/home"'),
        '-cp',('"'+$classpath.Replace('\','/')+'"'),'org.ip.Application',
        ('"--spring.config.location=classpath:application.properties,file:'+ $propertiesPath.Replace('\','/')+'[.properties]"'),
        "--spring.datasource.url=$url", "--server.port=$Port", '--server.address=127.0.0.1',
        '--spring.jpa.hibernate.ddl-auto=none','--spring.sql.init.mode=never',
        '--app.demo-data.enabled=false','--app.security-baseline.enabled=false','--app.accounting-pilot.seed=false',
        '--platform.navigation.enabled=false','--platform.rest.web.enabled=false',
        '--app.entity-explorer.ux-pilot.enabled=false','--vaadin.launch-browser=false',
        '--vaadin.allowed-packages=org.ip,org.ipro,com.vaadin,org.vaadin','"--vaadin.nodeFolder=C:/Program Files/nodejs"')
    [IO.File]::WriteAllLines($pilotArgs,[string[]]$arguments,[Text.UTF8Encoding]::new($false))
    $process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList ('"@'+$pilotArgs.Replace('\','/')+'"') `
        -WorkingDirectory $pilotRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$pilotOutput/server.log" -RedirectStandardError "$pilotOutput/server-error.log"
    @{processId=$process.Id; executable=$process.Path; startTimeUtcTicks=$process.StartTime.ToUniversalTime().Ticks} |
        ConvertTo-Json | Set-Content -LiteralPath $pilotStateFile -Encoding utf8
    Write-Output "Starting baseline stand: http://127.0.0.1:$Port/ (PID $($process.Id)). See server.log for readiness."
} finally { Pop-Location; $env:JAVA_HOME = $previousJavaHome }
