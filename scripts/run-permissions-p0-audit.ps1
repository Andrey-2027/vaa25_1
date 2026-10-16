[CmdletBinding()]
param(
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    [string]$Configuration = 'config/application-local.properties'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$p0Root = Split-Path -Parent $PSScriptRoot
$p0Settings = @{}
Get-Content -LiteralPath (Join-Path $p0Root $Configuration) | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $p0Settings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($p0Settings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'P0 audit expects a local PostgreSQL database without URL parameters.'
}
$p0Host = $matches[1]
$p0Port = $matches[2]
$p0Database = $matches[3]
$p0Output = Join-Path $p0Root 'outputs/permissions-p0'
New-Item -ItemType Directory -Force -Path $p0Output | Out-Null
$p0SavedPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
$p0SavedOptions = [Environment]::GetEnvironmentVariable('PGOPTIONS', 'Process')
try {
    $env:PGPASSWORD = $p0Settings['spring.datasource.password']
    # Read-only at connection level, in addition to the SQL transaction's explicit READ ONLY.
    $env:PGOPTIONS = '-c default_transaction_read_only=on -c statement_timeout=15000'
    & (Join-Path $PostgresBin 'psql.exe') '-h' $p0Host '-p' $p0Port `
        '-U' $p0Settings['spring.datasource.username'] '-d' $p0Database '-w' '-X' `
        '-v' 'ON_ERROR_STOP=1' '-P' 'pager=off' `
        '-f' (Join-Path $PSScriptRoot 'permissions-p0-audit.sql') `
        *> (Join-Path $p0Output 'database-audit.txt')
    if ($LASTEXITCODE -ne 0) { throw 'P0 read-only audit failed; inspect outputs/permissions-p0/database-audit.txt.' }
    Write-Output 'Read-only P0 database audit completed: outputs/permissions-p0/database-audit.txt'
} finally {
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $p0SavedPassword, 'Process')
    [Environment]::SetEnvironmentVariable('PGOPTIONS', $p0SavedOptions, 'Process')
}
