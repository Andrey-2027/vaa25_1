[CmdletBinding()]
param(
    [ValidateSet('Start','Stop','Restart','Status','Verify')][string]$Action = 'Start',
    [ValidateRange(1024,65535)][int]$Port = 8087,
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$NodeFolder = 'C:\Program Files\nodejs',
    [switch]$SkipBuild,
    [switch]$NavigationDisabled
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pilotRoot = Split-Path $PSScriptRoot -Parent
$pilotOutput = Join-Path $pilotRoot 'outputs/navigation-ki5'
$pilotStateFile = Join-Path $pilotRoot 'config/navigation-pilot.state.local.json'
$pilotProperties = Join-Path $pilotRoot 'config/navigation-pilot.properties.local'
$pilotPasswordFile = Join-Path $pilotRoot 'config/navigation-pilot.password.local'
$pilotArgumentFile = Join-Path $pilotOutput 'launch.args'
$pilotState = if (Test-Path $pilotStateFile) { Get-Content $pilotStateFile -Raw | ConvertFrom-Json } else { $null }

function Save-PilotState { $script:pilotState | ConvertTo-Json | Set-Content -LiteralPath $pilotStateFile -Encoding utf8 }
function Test-PilotProcess {
    if (!$script:pilotState -or !$script:pilotState.processId) { return $false }
    $owned = Get-CimInstance Win32_Process -Filter "ProcessId=$($script:pilotState.processId)" -ErrorAction SilentlyContinue
    if (!$owned) { return $false }
    if ($owned.Name -ne 'java.exe' -or !$owned.CommandLine.Contains('@'+$pilotArgumentFile)) {
        throw 'Stored PID no longer belongs to this pilot launcher; refusing to act on it.'
    }
    return $true
}
function Stop-Pilot {
    if (Test-PilotProcess) {
        Stop-Process -Id $script:pilotState.processId
        Write-Host 'Stopped the owned navigation pilot process. Database retained.'
    }
    if ($script:pilotState) { $script:pilotState.processId = 0; Save-PilotState }
}
function Escape-Property([string]$Value) { return $Value.Replace('\','\\').Replace("`r",'\r').Replace("`n",'\n') }

if ($pilotState -and !$pilotState.initialized -and (Test-PilotProcess)) {
    $readinessLog = Join-Path $pilotOutput 'server.log'
    if ((Test-Path $readinessLog) -and (Select-String -LiteralPath $readinessLog -SimpleMatch 'NAVIGATION_PILOT_READY' -Quiet)) {
        $pilotState.initialized = $true; Save-PilotState
    }
}
if ($Action -eq 'Stop') { Stop-Pilot; return }
if ($Action -eq 'Status') {
    if (!$pilotState) { Write-Host 'Pilot has not been prepared.'; return }
    Write-Host "Database: $($pilotState.database); running: $(Test-PilotProcess); port: $($pilotState.port)"
    return
}
if ($Action -eq 'Verify') {
    if (!$pilotState -or $pilotState.database -notmatch '^navigation_pilot_[a-f0-9]{32}$') { throw 'Prepare the pilot first.' }
    $verificationUrl = Get-Content $pilotProperties | Where-Object { $_ -like 'spring.datasource.url=*' }
    if ($verificationUrl -notmatch ('^spring.datasource.url=jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/'+$pilotState.database+'$')) {
        throw 'Unexpected pilot URL.'
    }
    $verificationHost = $matches[1]; $verificationPort = $matches[2]
    $verificationSettings = @{}
    Get-Content (Join-Path $pilotRoot 'config/application-local.properties') | ForEach-Object {
        if ($_ -match '^([^#=]+)=(.*)$') { $verificationSettings[$matches[1].Trim()] = $matches[2].Trim() }
    }
    $verificationPreviousPassword = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $verificationSettings['spring.datasource.password']
        & (Join-Path $PostgresBin 'psql.exe') '-h' $verificationHost '-p' $verificationPort `
            '-U' $verificationSettings['spring.datasource.username'] '-X' '-v' 'ON_ERROR_STOP=1' `
            '-d' $pilotState.database '-f' (Join-Path $PSScriptRoot 'navigation-pilot/verify.sql')
        if ($LASTEXITCODE -ne 0) { throw 'Pilot read-only verification failed.' }
    } finally { $env:PGPASSWORD = $verificationPreviousPassword }
    return
}
if ($Action -eq 'Restart') { Stop-Pilot }
if (Test-PilotProcess) { Write-Host "Pilot already running: http://127.0.0.1:$($pilotState.port)/"; return }
if (Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue) { throw "Port $Port is occupied." }

$pilotSavedEnvironment = @{}
foreach ($name in @('JAVA_HOME','PATH','PGPASSWORD')) { $pilotSavedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name,'Process') }
Push-Location -LiteralPath $pilotRoot
try {
    New-Item -ItemType Directory -Force -Path $pilotOutput | Out-Null
    $env:JAVA_HOME = $JavaHome
    if (!$SkipBuild) {
        & $Maven '-o' '-DskipTests' '-Dvaadin.skip=true' 'compile' 'dependency:build-classpath' `
            '-Dmdep.includeScope=runtime' '-Dmdep.outputFile=outputs/navigation-ki5/classpath.txt' *> (Join-Path $pilotOutput 'build.log')
        if ($LASTEXITCODE -ne 0) { throw 'Application build failed; see outputs/navigation-ki5/build.log.' }
    }
    $pilotClasspath = (Get-Content -LiteralPath (Join-Path $pilotOutput 'classpath.txt') -Raw).Trim()
    $pilotClasses = Join-Path $pilotOutput 'classes'
    New-Item -ItemType Directory -Force -Path $pilotClasses | Out-Null
    $pilotCompileArguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',
        ('"target/classes;'+$pilotClasspath+'"'),'-d',('"'+$pilotClasses+'"'))
    $pilotCompileArguments += Get-ChildItem (Join-Path $PSScriptRoot 'navigation-pilot/java') -Recurse -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName+'"' }
    $pilotCompileFile = Join-Path $pilotOutput 'compile.args'
    [IO.File]::WriteAllLines($pilotCompileFile,[string[]]($pilotCompileArguments | ForEach-Object { $_.Replace('\','/') }),[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') ('@'+$pilotCompileFile) *> (Join-Path $pilotOutput 'compile.log')
    if ($LASTEXITCODE -ne 0) { throw 'Pilot launcher compilation failed; see outputs/navigation-ki5/compile.log.' }

    if (!$pilotState) {
        if ($NavigationDisabled) { throw 'Prepare the enabled pilot first.' }
        $settings = @{}
        Get-Content -LiteralPath 'config/application-local.properties' | ForEach-Object {
            if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
        }
        if ($settings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
            throw 'Only local PostgreSQL without URL parameters is supported.'
        }
        $dbHost = $matches[1]; $dbPort = $matches[2]
        $database = 'navigation_pilot_' + [Guid]::NewGuid().ToString('N')
        $env:PGPASSWORD = $settings['spring.datasource.password']
        $connection = @('-h',$dbHost,'-p',$dbPort,'-U',$settings['spring.datasource.username'],'-X','-v','ON_ERROR_STOP=1')
        # Generated name only. Never clone data from, modify, or replace the configured working database.
        & (Join-Path $PostgresBin 'psql.exe') @connection '-d' 'postgres' '-c' "CREATE DATABASE $database"
        if ($LASTEXITCODE -ne 0) { throw 'Could not create separate PostgreSQL database.' }
        & (Join-Path $PostgresBin 'psql.exe') @connection '-d' $database '-f' 'platform-navigation/src/main/resources/db/navigation-postgresql.sql'
        if ($LASTEXITCODE -ne 0) { throw "Navigation DDL failed in $database." }
        & (Join-Path $PostgresBin 'psql.exe') @connection '-d' $database '-c' 'CREATE EXTENSION IF NOT EXISTS pg_trgm'
        if ($LASTEXITCODE -ne 0) { throw 'Could not prepare local PostgreSQL extension.' }
        $password = [Guid]::NewGuid().ToString('N')
        [IO.File]::WriteAllText($pilotPasswordFile,$password,[Text.UTF8Encoding]::new($false))
        $properties = @(
            "spring.datasource.url=jdbc:postgresql://${dbHost}:${dbPort}/$database",
            ('spring.datasource.username='+ (Escape-Property $settings['spring.datasource.username'])),
            ('spring.datasource.password='+ (Escape-Property $settings['spring.datasource.password'])),
            'spring.datasource.driver-class-name=org.postgresql.Driver',
            'spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect',
            "app.navigation-pilot.password=$password")
        [IO.File]::WriteAllLines($pilotProperties,$properties,[Text.UTF8Encoding]::new($false))
        $pilotState = [pscustomobject]@{ database=$database; initialized=$false; processId=0; port=$Port }
        Save-PilotState
    }
    if ($pilotState.database -notmatch '^navigation_pilot_[a-f0-9]{32}$') { throw 'Unexpected pilot state database name.' }
    $storedUrl = Get-Content $pilotProperties | Where-Object { $_ -like 'spring.datasource.url=*' }
    if ($storedUrl -notmatch ('^spring.datasource.url=jdbc:postgresql://(localhost|127\.0\.0\.1):\d+/'+$pilotState.database+'$')) {
        throw 'Stored pilot URL does not match the isolated database.'
    }
    $targetUrl = $storedUrl.Substring('spring.datasource.url='.Length)
    if (!(Test-Path (Join-Path $NodeFolder 'node.exe')) -or !(Test-Path 'node_modules/.bin/tsc.cmd')) {
        throw 'Existing Node.js and installed frontend dependencies are required.'
    }
    $env:PATH = (Join-Path $pilotRoot 'node_modules/.bin')+';'+$NodeFolder+';'+$env:PATH
    $schemaMode = if ($pilotState.initialized) { 'validate' } else { 'update' }
    $enabled = (!$NavigationDisabled).ToString().ToLowerInvariant()
    $pilotHome = Join-Path $pilotOutput 'home'
    New-Item -ItemType Directory -Force -Path $pilotHome | Out-Null
    $arguments = @('-Dfile.encoding=UTF-8', ('"-Duser.home='+$pilotHome+'"'), '-cp',
        ('"'+$pilotClasses+';target/classes;'+$pilotClasspath+'"'), 'org.navigationpilot.NavigationPilot',
        '--spring.profiles.active=navigation-pilot',
        ('"--spring.config.location=classpath:application.properties,file:'+ $pilotProperties.Replace('\','/')+'[.properties]"'),
        # Command-line URL wins over inherited SPRING_DATASOURCE_URL before Hibernate can write DDL.
        "--spring.datasource.url=$targetUrl",
        "--server.port=$Port", '--server.address=127.0.0.1', '--spring.sql.init.mode=never',
        "--spring.jpa.hibernate.ddl-auto=$schemaMode", "--platform.navigation.enabled=$enabled",
        "--app.navigation-pilot.seed=$enabled", '--app.demo-data.enabled=false',
        '--app.security-baseline.enabled=false', '--app.accounting-pilot.seed=false',
        '--platform.rest.web.enabled=false', '--vaadin.allowed-packages=org.ip,org.ipro,com.vaadin,org.vaadin',
        '--rls.write-boundary.enforced=true', '--vaadin.launch-browser=false',
        ('"--vaadin.nodeFolder='+$NodeFolder+'"'))
    [IO.File]::WriteAllLines($pilotArgumentFile,[string[]]($arguments | ForEach-Object { $_.Replace('\','/') }),[Text.UTF8Encoding]::new($false))
    $process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList ('"@'+$pilotArgumentFile+'"') `
        -WorkingDirectory $pilotRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $pilotOutput 'server.log') -RedirectStandardError (Join-Path $pilotOutput 'server-error.log')
    $pilotState.processId = $process.Id; $pilotState.port = $Port; Save-PilotState
    Write-Host "Starting isolated pilot: http://127.0.0.1:$Port/ (PID $($process.Id), schema $schemaMode)"
    Write-Host "Users: ki-admin / ki-a / ki-b. Password file: $pilotPasswordFile"
    $readyMessage = if ($NavigationDisabled) { 'Started NavigationPilot' } else { 'NAVIGATION_PILOT_READY' }
    Write-Host "Readiness: $readyMessage in outputs/navigation-ki5/server.log. Stop with -Action Stop."
    # Readiness is recorded by a subsequent Start/Status call only after the seed committed.
} finally {
    Pop-Location
    foreach ($name in $pilotSavedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$pilotSavedEnvironment[$name],'Process') }
}
