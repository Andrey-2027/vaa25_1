param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    # Пусто — обычный локальный репозиторий Maven (~/.m2), тот же, что у H2-приёмок.
    [string]$LocalRepository = ''
)
# ADR-0025, PostgreSQL-прогон lifecycle строки регистра на пути набора владельца: изолированная
# база на реальном сервере. Проверяется то, чего H2 не показывает: счетчик хуков на настоящем
# flush (ровно один вызов на вставленную строку), откат выделенного порядка записи вместе с вето
# хука и то, что соседние пути записи (гонки набора, гонки по ключу) на общем реестре не сломаны.
$ErrorActionPreference = 'Stop'
$adrRoot = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $adrRoot
$adrSettings = @{}
Get-Content -LiteralPath 'config/application-local.properties' | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $adrSettings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($adrSettings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'Expected a local PostgreSQL URL without query parameters.'
}
$adrHost = $matches[1]; $adrPort = $matches[2]
$adrDb = 'g3_register_test_adr0025_' + [Guid]::NewGuid().ToString('N')
if ($adrDb -notmatch '^g3_register_test_adr0025_[a-f0-9]{32}$') { throw 'Invalid isolated database name.' }
$adrSavedEnvironment = @{}
foreach ($adrVariable in @('PGPASSWORD','JAVA_HOME','IPRO_REGISTER_G3_JDBC_URL','IPRO_REGISTER_G3_DB_USER','IPRO_REGISTER_G3_DB_PASSWORD')) {
    $adrSavedEnvironment[$adrVariable] = [Environment]::GetEnvironmentVariable($adrVariable, 'Process')
}
try {
    $env:PGPASSWORD = $adrSettings['spring.datasource.password']
    & (Join-Path $PostgresBin 'psql.exe') '-h' $adrHost '-p' $adrPort '-U' $adrSettings['spring.datasource.username'] `
        '-X' '-v' 'ON_ERROR_STOP=1' '-d' 'postgres' '-c' "CREATE DATABASE $adrDb"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create a fresh ADR-0025 test database.' }
    Write-Host "Isolated PostgreSQL test database: $adrDb (retained for diagnosis)."
    $env:IPRO_REGISTER_G3_JDBC_URL = "jdbc:postgresql://${adrHost}:${adrPort}/$adrDb"
    $env:IPRO_REGISTER_G3_DB_USER = $adrSettings['spring.datasource.username']
    $env:IPRO_REGISTER_G3_DB_PASSWORD = $adrSettings['spring.datasource.password']
    $env:JAVA_HOME = 'C:\Program Files\Axiom\AxiomJDK-21'
    $adrAgent = Join-Path $adrRoot '.local-maven-repository/net/bytebuddy/byte-buddy-agent/1.17.8/byte-buddy-agent-1.17.8.jar'
    $adrArguments = [System.Collections.Generic.List[string]]::new()
    $adrArguments.Add('-o')
    if ($LocalRepository) { $adrArguments.Add("-Dmaven.repo.local=$LocalRepository") }
    # D-R S5(в): все три класса — тесты движка, они переехали в platform-register и гоняются его
    # сборкой. Из корневого проекта они больше не запускаются: на чистом checkout это
    # «No tests matching pattern» (surefire 3.5.5 при failIfNoSpecifiedTests=true падает даже
    # с `-DfailIfNoTests=false`), а на машине с остатками `target/test-classes` — прогон
    # устаревших скомпилированных копий, то есть гейт не мерил модуль в обоих случаях. Путь
    # агента абсолютный: для дочернего JVM рабочий каталог — каталог модуля.
    $adrArguments.Add('-f')
    $adrArguments.Add('platform-register/pom.xml')
    $adrArguments.Add("-DargLine=-javaagent:$adrAgent")
    # Приёмка ADR-0025 плюс соседние пути на том же реестре: правило строки подключается к
    # писателю набора, поэтому «набор не сломался» доказывается вместе с ним, а не отдельным прогоном.
    $adrArguments.Add('-Dtest=RecorderSetWriterPostgresIT,RegisterWriteConcurrencyPostgresIT,RegisterKeyWriteConcurrencyPostgresIT')
    $adrArguments.Add('test')
    & $Maven @adrArguments
    if ($LASTEXITCODE -ne 0) { throw 'ADR-0025 PostgreSQL gate failed; inspect the Maven output.' }
} finally {
    foreach ($adrVariable in $adrSavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($adrVariable, $adrSavedEnvironment[$adrVariable], 'Process')
    }
}
