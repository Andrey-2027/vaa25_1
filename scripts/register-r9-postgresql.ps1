param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    # Пусто — обычный локальный репозиторий Maven (~/.m2), тот же, что у H2-приёмки.
    # Явный путь имеет смысл только для закреплённого набора артефактов.
    [string]$LocalRepository = ''
)
# Р9 PostgreSQL-профиль: те же наборы, что приняты на H2, но на изолированной базе
# реального сервера. Рабочая база не затрагивается: имя базы всегда новое и удаляется
# только вручную для диагностики.
$ErrorActionPreference = 'Stop'
$r9Root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $r9Root
$r9Settings = @{}
Get-Content -LiteralPath 'config/application-local.properties' | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $r9Settings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($r9Settings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'Expected a local PostgreSQL URL without query parameters.'
}
$r9Host = $matches[1]; $r9Port = $matches[2]
$r9Db = 'g3_register_test_r9_' + [Guid]::NewGuid().ToString('N')
if ($r9Db -notmatch '^g3_register_test_r9_[a-f0-9]{32}$') { throw 'Invalid isolated database name.' }
$r9SavedEnvironment = @{}
foreach ($r9Variable in @('PGPASSWORD','JAVA_HOME','SPRING_DATASOURCE_URL','SPRING_DATASOURCE_USERNAME',
    'SPRING_DATASOURCE_PASSWORD','SPRING_DATASOURCE_DRIVER_CLASS_NAME','SPRING_JPA_DATABASE_PLATFORM')) {
    $r9SavedEnvironment[$r9Variable] = [Environment]::GetEnvironmentVariable($r9Variable, 'Process')
}
try {
    $env:PGPASSWORD = $r9Settings['spring.datasource.password']
    & (Join-Path $PostgresBin 'psql.exe') '-h' $r9Host '-p' $r9Port '-U' $r9Settings['spring.datasource.username'] `
        '-X' '-v' 'ON_ERROR_STOP=1' '-d' 'postgres' '-c' "CREATE DATABASE $r9Db"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh R9 test database.' }
    Write-Host "Isolated PostgreSQL test database: $r9Db (retained for diagnosis)."
    $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://${r9Host}:${r9Port}/$r9Db"
    $env:SPRING_DATASOURCE_USERNAME = $r9Settings['spring.datasource.username']
    $env:SPRING_DATASOURCE_PASSWORD = $r9Settings['spring.datasource.password']
    $env:SPRING_DATASOURCE_DRIVER_CLASS_NAME = 'org.postgresql.Driver'
    $env:SPRING_JPA_DATABASE_PLATFORM = 'org.hibernate.dialect.PostgreSQLDialect'
    $env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
    $r9Agent = Join-Path $r9Root '.local-maven-repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar'
    $r9Tests = 'RegisterBatchReadIT,AccountingObjectPilotIT'
    $r9MavenArguments = [System.Collections.Generic.List[string]]::new()
    $r9MavenArguments.Add('-o')
    if ($LocalRepository) { $r9MavenArguments.Add("-Dmaven.repo.local=$LocalRepository") }
    $r9MavenArguments.Add('-Dvaadin.skip=true')
    $r9MavenArguments.Add("-DargLine=-javaagent:$r9Agent")
    $r9MavenArguments.Add("-Dtest=$r9Tests")
    $r9MavenArguments.Add('test')
    & $Maven @r9MavenArguments
    if ($LASTEXITCODE -ne 0) { throw 'R9 PostgreSQL verification failed; inspect the Maven output.' }
} finally {
    foreach ($r9Variable in $r9SavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($r9Variable, $r9SavedEnvironment[$r9Variable], 'Process')
    }
}
