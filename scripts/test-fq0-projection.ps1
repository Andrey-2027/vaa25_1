param(
    [string]$Maven = 'C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd',
    [string]$Jdk = 'C:/Program Files/Axiom/AxiomJDK-21',
    [string]$Psql = 'C:/Program Files/PostgreSQL/17/bin/psql.exe',
    [string]$LocalProperties,
    [ValidateSet('Fq0ProjectionIT', 'QueryListRegistryTest')]
    [string]$Test = 'Fq0ProjectionIT',
    [switch]$MeasureHeap
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
if (!$LocalProperties) { throw 'Pass the local PostgreSQL properties file using -LocalProperties.' }
$config = @{}
Get-Content -LiteralPath $LocalProperties | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $config[$Matches[1].Trim()] = $Matches[2].Trim() }
}
if ($config['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/[a-zA-Z0-9_]+$') {
    throw 'Expected a local PostgreSQL URL.'
}
$dbHost = $Matches[1]
$dbPort = $Matches[2]
$dbName = 'fq0_' + [guid]::NewGuid().ToString('N')
$connection = @('-h',$dbHost,'-p',$dbPort,'-U',$config['spring.datasource.username'],'-X','-v','ON_ERROR_STOP=1','-d','postgres')
$previous = @{}
foreach ($name in @('PGPASSWORD','JAVA_HOME','FQ0_TEST_JDBC_USERNAME','FQ0_TEST_JDBC_PASSWORD','FQ0_TEST_JDBC_URL')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
$created = $false
try {
    $env:PGPASSWORD = $config['spring.datasource.password']
    $env:FQ0_TEST_JDBC_USERNAME = $config['spring.datasource.username']
    $env:FQ0_TEST_JDBC_PASSWORD = $env:PGPASSWORD
    $env:FQ0_TEST_JDBC_URL = "jdbc:postgresql://${dbHost}:${dbPort}/$dbName"
    $env:JAVA_HOME = $Jdk
    & $Psql @connection -c "CREATE DATABASE $dbName"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create temporary FQ-0 database.' }
    $created = $true

    $repo = Join-Path $root '.local-maven-repository'
    $pom = Join-Path $root 'platform-query/pom.xml'
    $mavenArgs = @('-o', "-Dmaven.repo.local=$repo", '-f', $pom, "-Dtest=$Test")
    if ($MeasureHeap) { $mavenArgs += '-Dfq0.measureHeap=true' }
    & $Maven @mavenArgs test
    if ($LASTEXITCODE -ne 0) { throw "Projection module test $Test failed." }
} finally {
    if ($created) {
        & $Psql @connection -c "DROP DATABASE $dbName"
        if ($LASTEXITCODE -ne 0) { Write-Warning "Could not remove temporary database $dbName." }
    }
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}

