[CmdletBinding()]
param()
$settings = @{}
Get-Content -LiteralPath "$PSScriptRoot/../config/application-local.properties" | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($settings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'Only local PostgreSQL is allowed'
}
$hostName = $matches[1]; $port = $matches[2]; $database = $matches[3]
$previous = $env:PGPASSWORD
try {
    $env:PGPASSWORD = $settings['spring.datasource.password']
    & 'C:/Program Files/PostgreSQL/17/bin/psql.exe' -h $hostName -p $port -U $settings['spring.datasource.username'] `
        -d $database -w -X -v ON_ERROR_STOP=1 -P pager=off -f "$PSScriptRoot/permissions-p4-unique.sql"
    if ($LASTEXITCODE -ne 0) { throw 'P4 unique index installation failed' }
} finally { $env:PGPASSWORD = $previous }
