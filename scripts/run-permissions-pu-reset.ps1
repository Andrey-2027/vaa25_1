[CmdletBinding()]
param(
    [switch]$Apply,
    [string]$Configuration = 'config/application-local.properties',
    [string]$ExpectedDatabase = 'DBVaa25',
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$puResetRoot = Split-Path -Parent $PSScriptRoot
$puResetSettings = @{}
Get-Content -LiteralPath (Join-Path $puResetRoot $Configuration) -Encoding UTF8 | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $puResetSettings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($puResetSettings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'P-U reset is restricted to a local database without URL parameters.'
}
$puResetHost = $matches[1]; $puResetPort = $matches[2]; $puResetDatabase = $matches[3]
if ($puResetDatabase -cne $ExpectedDatabase) { throw 'Configured database differs from the explicitly expected development database.' }
$puResetOutput = Join-Path $puResetRoot 'outputs/permissions-pu'
New-Item -ItemType Directory -Force -Path $puResetOutput | Out-Null
$puResetPasswordBefore = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
$puResetOptionsBefore = [Environment]::GetEnvironmentVariable('PGOPTIONS', 'Process')
try {
    $env:PGPASSWORD = $puResetSettings['spring.datasource.password']
    $env:PGOPTIONS = if ($Apply) { '-c statement_timeout=15000 -c lock_timeout=5000' } else { '-c default_transaction_read_only=on -c statement_timeout=15000' }
    $puResetArgs = @('-h',$puResetHost,'-p',$puResetPort,'-U',$puResetSettings['spring.datasource.username'],
        '-d',$puResetDatabase,'-w','-X','-v','ON_ERROR_STOP=1','-P','pager=off')
    if ($Apply) {
        $puResetArgs += @('-v',('expected_database=' + $ExpectedDatabase),'-f',(Join-Path $PSScriptRoot 'permissions-pu-reset.sql'))
    } else {
        $puResetArgs += @('-c',"select table_name, column_name, column_default, is_nullable, is_identity from information_schema.columns where table_schema='public' and table_name in ('access_grant','user_role') order by table_name, ordinal_position")
    }
    $puResetLog = Join-Path $puResetOutput $(if ($Apply) { 'database-reset.txt' } else { 'database-schema.txt' })
    & (Join-Path $PostgresBin 'psql.exe') @puResetArgs *> $puResetLog
    if ($LASTEXITCODE -ne 0) { Get-Content -LiteralPath $puResetLog -Tail 18; throw 'P-U database operation failed; no successful commit is confirmed.' }
    Get-Content -LiteralPath $puResetLog
} finally {
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $puResetPasswordBefore, 'Process')
    [Environment]::SetEnvironmentVariable('PGOPTIONS', $puResetOptionsBefore, 'Process')
}
