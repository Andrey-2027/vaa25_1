param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    # Пусто — обычный локальный репозиторий Maven (~/.m2), тот же, что у H2-приёмок.
    [string]$LocalRepository = ''
)
# D-R S3, PostgreSQL-прогон протокола набора владельца: изолированная база на реальном сервере.
# Проверяется то, что H2 не проверяет: блокировки строки порядка под набором владельца, откат
# попытки, поведение при конфликте версий документа и независимая авторизация каждой строки.
$ErrorActionPreference = 'Stop'
$r3Root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $r3Root
$r3Settings = @{}
Get-Content -LiteralPath 'config/application-local.properties' | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $r3Settings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($r3Settings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'Expected a local PostgreSQL URL without query parameters.'
}
$r3Host = $matches[1]; $r3Port = $matches[2]
$r3Db = 'g3_register_test_r11_' + [Guid]::NewGuid().ToString('N')
if ($r3Db -notmatch '^g3_register_test_r11_[a-f0-9]{32}$') { throw 'Invalid isolated database name.' }
$r3SavedEnvironment = @{}
foreach ($r3Variable in @('PGPASSWORD','JAVA_HOME','IPRO_REGISTER_G3_JDBC_URL','IPRO_REGISTER_G3_DB_USER','IPRO_REGISTER_G3_DB_PASSWORD')) {
    $r3SavedEnvironment[$r3Variable] = [Environment]::GetEnvironmentVariable($r3Variable, 'Process')
}
try {
    $env:PGPASSWORD = $r3Settings['spring.datasource.password']
    & (Join-Path $PostgresBin 'psql.exe') '-h' $r3Host '-p' $r3Port '-U' $r3Settings['spring.datasource.username'] `
        '-X' '-v' 'ON_ERROR_STOP=1' '-d' 'postgres' '-c' "CREATE DATABASE $r3Db"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh S3 test database.' }
    Write-Host "Isolated PostgreSQL test database: $r3Db (retained for diagnosis)."
    $env:IPRO_REGISTER_G3_JDBC_URL = "jdbc:postgresql://${r3Host}:${r3Port}/$r3Db"
    $env:IPRO_REGISTER_G3_DB_USER = $r3Settings['spring.datasource.username']
    $env:IPRO_REGISTER_G3_DB_PASSWORD = $r3Settings['spring.datasource.password']
    $env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
    $r3Agent = Join-Path $r3Root '.local-maven-repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar'
    $r3Arguments = [System.Collections.Generic.List[string]]::new()
    $r3Arguments.Add('-o')
    if ($LocalRepository) { $r3Arguments.Add("-Dmaven.repo.local=$LocalRepository") }
    # D-R S5(в): оба класса — тесты движка и переехали в platform-register. Gate среза гоняется
    # сборкой модуля: путь агента абсолютный, так как рабочий каталог дочернего JVM — каталог модуля.
    $r3Arguments.Add('-f')
    $r3Arguments.Add('platform-register/pom.xml')
    $r3Arguments.Add("-DargLine=-javaagent:$r3Agent")
    # Набор владельца под документом и гонки записи по ключу на том же мьютексе: срез S3 взял
    # часы порядка у S2, поэтому оба класса обязаны пройти вместе, а не по отдельности.
    $r3Arguments.Add('-Dtest=RecorderSetWriterPostgresIT,RegisterWriteConcurrencyPostgresIT')
    $r3Arguments.Add('-DfailIfNoTests=false')
    $r3Arguments.Add('test')
    & $Maven @r3Arguments
    if ($LASTEXITCODE -ne 0) { throw 'S3 PostgreSQL gate failed; inspect the Maven output.' }
} finally {
    foreach ($r3Variable in $r3SavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($r3Variable, $r3SavedEnvironment[$r3Variable], 'Process')
    }
}
