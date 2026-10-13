param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    # Пусто — обычный локальный репозиторий Maven (~/.m2), тот же, что у H2-приёмок.
    [string]$LocalRepository = ''
)
# Р11, PostgreSQL-прогон записи по ключу: изолированная база на реальном сервере.
# Гонки отсутствующего ключа, уникальности и блокировок нельзя проверить на H2.
$ErrorActionPreference = 'Stop'
$r11Root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $r11Root
$r11Settings = @{}
Get-Content -LiteralPath 'config/application-local.properties' | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $r11Settings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($r11Settings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'Expected a local PostgreSQL URL without query parameters.'
}
$r11Host = $matches[1]; $r11Port = $matches[2]
$r11Db = 'g3_register_test_r11_' + [Guid]::NewGuid().ToString('N')
if ($r11Db -notmatch '^g3_register_test_r11_[a-f0-9]{32}$') { throw 'Invalid isolated database name.' }
$r11SavedEnvironment = @{}
foreach ($r11Variable in @('PGPASSWORD','JAVA_HOME','IPRO_REGISTER_G3_JDBC_URL','IPRO_REGISTER_G3_DB_USER','IPRO_REGISTER_G3_DB_PASSWORD')) {
    $r11SavedEnvironment[$r11Variable] = [Environment]::GetEnvironmentVariable($r11Variable, 'Process')
}
try {
    $env:PGPASSWORD = $r11Settings['spring.datasource.password']
    & (Join-Path $PostgresBin 'psql.exe') '-h' $r11Host '-p' $r11Port '-U' $r11Settings['spring.datasource.username'] `
        '-X' '-v' 'ON_ERROR_STOP=1' '-d' 'postgres' '-c' "CREATE DATABASE $r11Db"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh R11 test database.' }
    Write-Host "Isolated PostgreSQL test database: $r11Db (retained for diagnosis)."
    $env:IPRO_REGISTER_G3_JDBC_URL = "jdbc:postgresql://${r11Host}:${r11Port}/$r11Db"
    $env:IPRO_REGISTER_G3_DB_USER = $r11Settings['spring.datasource.username']
    $env:IPRO_REGISTER_G3_DB_PASSWORD = $r11Settings['spring.datasource.password']
    $env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
    $r11Agent = Join-Path $r11Root '.local-maven-repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar'
    $r11Arguments = [System.Collections.Generic.List[string]]::new()
    $r11Arguments.Add('-o')
    if ($LocalRepository) { $r11Arguments.Add("-Dmaven.repo.local=$LocalRepository") }
    $r11Arguments.Add('-Dvaadin.skip=true')
    $r11Arguments.Add("-DargLine=-javaagent:$r11Agent")
    # Спайк контракта и приёмка реализации: оба класса гонок требуют реального сервера.
    $r11Arguments.Add('-Dtest=RegisterKeyWriteConcurrencyPostgresIT,RegisterKeyWritesPostgresIT')
    $r11Arguments.Add('-DfailIfNoTests=false')
    $r11Arguments.Add('test')
    & $Maven @r11Arguments
    if ($LASTEXITCODE -ne 0) { throw 'R11 PostgreSQL spike failed; inspect the Maven output.' }
} finally {
    foreach ($r11Variable in $r11SavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($r11Variable, $r11SavedEnvironment[$r11Variable], 'Process')
    }
}
