param(
    [ValidateSet('All','Concurrency')] [string]$Suite = 'All',
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd'
)
$ErrorActionPreference = 'Stop'
$r8Root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $r8Root
$r8Settings = @{}
Get-Content -LiteralPath 'config/application-local.properties' | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $r8Settings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($r8Settings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'Expected a local PostgreSQL URL without query parameters.'
}
$r8Host = $matches[1]; $r8Port = $matches[2]
$r8Db = 'g3_register_test_r8_' + [Guid]::NewGuid().ToString('N')
if ($r8Db -notmatch '^g3_register_test_r8_[a-f0-9]{32}$') { throw 'Invalid isolated database name.' }
$r8SavedEnvironment = @{}
foreach ($r8Variable in @('PGPASSWORD','JAVA_HOME','IPRO_REGISTER_G3_JDBC_URL','IPRO_REGISTER_G3_DB_USER',
    'IPRO_REGISTER_G3_DB_PASSWORD','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME','SPRING_DATASOURCE_PASSWORD',
    'SPRING_DATASOURCE_DRIVER_CLASS_NAME','SPRING_JPA_DATABASE_PLATFORM')) {
    $r8SavedEnvironment[$r8Variable] = [Environment]::GetEnvironmentVariable($r8Variable, 'Process')
}
try {
    $env:PGPASSWORD = $r8Settings['spring.datasource.password']
    & (Join-Path $PostgresBin 'psql.exe') '-h' $r8Host '-p' $r8Port '-U' $r8Settings['spring.datasource.username'] `
        '-X' '-v' 'ON_ERROR_STOP=1' '-d' 'postgres' '-c' "CREATE DATABASE $r8Db"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh R8 test database.' }
    Write-Host "Isolated PostgreSQL test database: $r8Db (retained for diagnosis)."
    $env:IPRO_REGISTER_G3_JDBC_URL = "jdbc:postgresql://${r8Host}:${r8Port}/$r8Db"
    $env:IPRO_REGISTER_G3_DB_USER = $r8Settings['spring.datasource.username']
    $env:IPRO_REGISTER_G3_DB_PASSWORD = $r8Settings['spring.datasource.password']
    $env:SPRING_DATASOURCE_URL = $env:IPRO_REGISTER_G3_JDBC_URL
    $env:SPRING_DATASOURCE_USERNAME = $env:IPRO_REGISTER_G3_DB_USER
    $env:SPRING_DATASOURCE_PASSWORD = $env:IPRO_REGISTER_G3_DB_PASSWORD
    $env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'org.postgresql.Driver'
    $env:SPRING_JPA_DATABASE_PLATFORM = 'org.hibernate.dialect.PostgreSQLDialect'
    $env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
    $r8Agent = Join-Path $r8Root '.local-maven-repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar'
    $r8Tests = if ($Suite -eq 'Concurrency') { 'RegisterWriteConcurrencyPostgresIT' } else {
        'RegisterWriteConcurrencyPostgresIT,RecorderSetWriterPostgresIT,AccountingObjectPilotIT'
    }
    & $Maven '-o' '-Dmaven.repo.local=.local-maven-repository' '-Dvaadin.skip=true' "-DargLine=-javaagent:$r8Agent" `
        "-Dtest=$r8Tests" 'test'
    if ($LASTEXITCODE -ne 0) { throw 'R8 PostgreSQL verification failed; inspect the Maven output.' }
} finally {
    foreach ($r8Variable in $r8SavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($r8Variable, $r8SavedEnvironment[$r8Variable], 'Process')
    }
}
