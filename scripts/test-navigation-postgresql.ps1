[CmdletBinding()]
param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21'
)
$ErrorActionPreference = 'Stop'
$navigationRoot = Split-Path $PSScriptRoot -Parent
$navigationSettings = @{}
Get-Content -LiteralPath (Join-Path $navigationRoot 'config/application-local.properties') | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $navigationSettings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($navigationSettings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'Navigation verification requires local PostgreSQL without URL parameters.'
}
$navigationHost = $matches[1]
$navigationPort = $matches[2]
$navigationDatabase = 'navigation_ki2_' + [Guid]::NewGuid().ToString('N')
$navigationSavedEnvironment = @{}
foreach ($navigationVariable in @('PGPASSWORD','JAVA_HOME','NAVIGATION_TEST_JDBC_URL','NAVIGATION_TEST_JDBC_USERNAME','NAVIGATION_TEST_JDBC_PASSWORD')) {
    $navigationSavedEnvironment[$navigationVariable] = [Environment]::GetEnvironmentVariable($navigationVariable, 'Process')
}
$navigationCreated = $false
$navigationPsql = Join-Path $PostgresBin 'psql.exe'
$navigationConnection = @('-h',$navigationHost,'-p',$navigationPort,'-U',$navigationSettings['spring.datasource.username'],'-X','-v','ON_ERROR_STOP=1','-d','postgres')
try {
    $env:PGPASSWORD = $navigationSettings['spring.datasource.password']
    & $navigationPsql @navigationConnection '-c' "CREATE DATABASE $navigationDatabase"
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated navigation test database.' }
    $navigationCreated = $true
    $env:JAVA_HOME = $JavaHome
    $env:NAVIGATION_TEST_JDBC_URL = "jdbc:postgresql://${navigationHost}:${navigationPort}/$navigationDatabase"
    $env:NAVIGATION_TEST_JDBC_USERNAME = $navigationSettings['spring.datasource.username']
    $env:NAVIGATION_TEST_JDBC_PASSWORD = $navigationSettings['spring.datasource.password']
    Push-Location -LiteralPath $navigationRoot
    try {
        & $Maven '-o' '-f' 'platform-navigation/pom.xml' 'test' '-Dtest=NavigationPostgresqlTest'
        if ($LASTEXITCODE -ne 0) { throw 'Navigation PostgreSQL verification failed.' }
    } finally { Pop-Location }
} finally {
    if ($navigationCreated -and $navigationDatabase -match '^navigation_ki2_[a-f0-9]{32}$') {
        & $navigationPsql @navigationConnection '-c' "DROP DATABASE $navigationDatabase"
        if ($LASTEXITCODE -ne 0) { Write-Warning "Isolated database cleanup failed: $navigationDatabase" }
    }
    foreach ($navigationVariable in $navigationSavedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($navigationVariable, $navigationSavedEnvironment[$navigationVariable], 'Process')
    }
}
