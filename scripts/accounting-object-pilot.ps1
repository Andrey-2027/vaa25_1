param(
    [ValidateSet('Prepare','Run','Verify')] [string]$Mode = 'Run',
    [ValidatePattern('^accounting_object_pilot_[a-z0-9_]+$')]
    [ValidateLength(1,63)]
    [string]$Database = 'accounting_object_pilot_local_v3',
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$pilotRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $pilotRoot
$pilotSettings = @{}
Get-Content -LiteralPath 'config/application-local.properties' | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $pilotSettings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($pilotSettings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'This script requires a local PostgreSQL URL without query parameters.'
}
$pilotHost = $matches[1]; $pilotPort = $matches[2]; $pilotSource = $matches[3]
$pilotUser = $pilotSettings['spring.datasource.username']
$pilotOriginalEnvironment = @{}
foreach ($pilotVariable in @('PGPASSWORD','JAVA_HOME','SPRING_DATASOURCE_USERNAME',
        'SPRING_DATASOURCE_PASSWORD','SPRING_DATASOURCE_DRIVER_CLASS_NAME',
        'SPRING_JPA_DATABASE_PLATFORM','SPRING_DATASOURCE_URL','APP_ACCOUNTING_PILOT_PASSWORD')) {
    $pilotOriginalEnvironment[$pilotVariable] = [Environment]::GetEnvironmentVariable($pilotVariable, 'Process')
}
$env:PGPASSWORD = $pilotSettings['spring.datasource.password']
$pilotPsql = Join-Path $PostgresBin 'psql.exe'
$pilotConnection = @('-h',$pilotHost,'-p',$pilotPort,'-U',$pilotUser,'-X','-v','ON_ERROR_STOP=1')
function Invoke-PilotSql([string]$Db, [string[]]$Arguments) {
    & $pilotPsql @pilotConnection '-d' $Db @Arguments
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL operation failed.' }
}
function New-PilotDatabase([string]$Db) {
    # Names reaching SQL are fixed or validated; the source database is never a write target.
    if ($Db -notmatch '^(accounting_object_pilot_|g3_register_test_accounting_pilot_)[a-z0-9_]+$') {
        throw 'Unexpected target database name.'
    }
    $pilotExists = & $pilotPsql @pilotConnection '-d' 'postgres' '-At' '-c' "select 1 from pg_database where datname='$Db'"
    if ($LASTEXITCODE -ne 0) { throw 'Database inventory failed.' }
    if ($pilotExists -eq '1') { return $false }
    Invoke-PilotSql 'postgres' @('-c',"CREATE DATABASE $Db")
    return $true
}
try {
    if ($Mode -eq 'Prepare') {
        if (!(New-PilotDatabase $Database)) {
            throw "Database $Database already exists. Preparation never replaces an existing database."
        }
        $pilotDump = Join-Path $pilotRoot 'target/accounting-pilot-base-schema.sql'
        New-Item -ItemType Directory -Path (Split-Path $pilotDump) -Force | Out-Null
        & (Join-Path $PostgresBin 'pg_dump.exe') '-h' $pilotHost '-p' $pilotPort '-U' $pilotUser `
            '--schema-only' '--no-owner' '--no-acl' '--schema=public' '--file' $pilotDump $pilotSource
        if ($LASTEXITCODE -ne 0) { throw 'Baseline schema export failed.' }
        $pilotSchemaSql = [IO.File]::ReadAllText($pilotDump).Replace('CREATE SCHEMA public;', 'CREATE SCHEMA IF NOT EXISTS public;')
        [IO.File]::WriteAllText($pilotDump, $pilotSchemaSql, [Text.UTF8Encoding]::new($false))
        Invoke-PilotSql $Database @('-c','CREATE EXTENSION IF NOT EXISTS pg_trgm;')
        Invoke-PilotSql $Database @('-f',$pilotDump)
        Invoke-PilotSql $Database @('-f','scripts/information-register-period-timestamp-postgresql.sql')
        Invoke-PilotSql $Database @('-f','scripts/information-register-recorder-clock-postgresql.sql')
        Invoke-PilotSql $Database @('-f','scripts/accounting-object-pilot-postgresql.sql')
        Write-Host "Prepared isolated database $Database. Run this script with -Mode Run."
        return
    }
    if (!(Test-Path -LiteralPath $Maven)) { throw 'Supply the Maven executable with -Maven.' }
    if (Test-Path 'C:\Program Files\Axiom\AxiomJDK-21') { $env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21' }
    $env:SPRING_DATASOURCE_USERNAME = $pilotUser
    $env:SPRING_DATASOURCE_PASSWORD = $pilotSettings['spring.datasource.password']
    $env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'org.postgresql.Driver'
    $env:SPRING_JPA_DATABASE_PLATFORM = 'org.hibernate.dialect.PostgreSQLDialect'
    if ($Mode -eq 'Verify') {
        # create-drop tests receive their own fresh database, never the interactive pilot database.
        $pilotTestDb = 'g3_register_test_accounting_pilot_' + [Guid]::NewGuid().ToString('N').Substring(0,24)
        New-PilotDatabase $pilotTestDb | Out-Null
        $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${pilotHost}:${pilotPort}/$pilotTestDb"
        & $Maven '-o' '-Dmaven.repo.local=.local-maven-repository' '-Dvaadin.skip=true' `
            '-Dtest=AccountingObjectPilotIT' 'test'
    } else {
        $pilotLoginFile = Join-Path $pilotRoot "config/$Database.login.local"
        if (!(Test-Path -LiteralPath $pilotLoginFile)) {
            [IO.File]::WriteAllText($pilotLoginFile, [Guid]::NewGuid().ToString('N'), [Text.UTF8Encoding]::new($false))
        }
        $env:APP_ACCOUNTING_PILOT_PASSWORD = [IO.File]::ReadAllText($pilotLoginFile).Trim()
        Write-Host "Local pilot login: accounting-pilot. Password file: $pilotLoginFile"
        $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${pilotHost}:${pilotPort}/$Database"
        & $Maven '-o' '-Dmaven.repo.local=.local-maven-repository' '-Dvaadin.skip=true' `
            "-Dspring-boot.run.jvmArguments=-Duser.home=$($env:USERPROFILE)" `
            '-Dspring-boot.run.profiles=accounting-pilot' 'spring-boot:run'
    }
    if ($LASTEXITCODE -ne 0) { throw 'Pilot Maven command failed; inspect the build output.' }
} finally {
    foreach ($pilotVariable in $pilotOriginalEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($pilotVariable, $pilotOriginalEnvironment[$pilotVariable], 'Process')
    }
}
