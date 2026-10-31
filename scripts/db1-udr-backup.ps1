param(
    # Пусто — БД из config/application-local.properties (локальный дефолт).
    [string[]]$Databases = @(),
    # Пусто — outputs/db1-udr-backup-<stamp> (каталог gitignored). Для прод-бэкапа задать явно.
    [string]$OutputRoot = '',
    [string]$PostgresBin = 'C:\Program Files\PostgreSQL\17\bin',
    # Проверка восстановления на одноразовой БД; отключать только для пустых БД, где дампа нет.
    [switch]$SkipRestoreCheck,
    # Скрипт по умолчанию работает только с localhost: рабочая БД — явное решение оператора.
    [switch]$AllowRemote
)
# DB1 UDR: снять дамп пяти таблиц движка и проверить восстановление до удаления.
# Ничего не удаляет: только pg_dump/psql/createdb/dropdb и временная БД проверки.
$ErrorActionPreference = 'Stop'
$db1Root = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $db1Root

$db1Settings = @{}
Get-Content -LiteralPath 'config/application-local.properties' | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $db1Settings[$matches[1].Trim()] = $matches[2].Trim() }
}
$db1Url = $db1Settings['spring.datasource.url']
if ($db1Url -notmatch '^jdbc:postgresql://([^/:]+):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'Ожидался локальный PostgreSQL URL без параметров: jdbc:postgresql://host:port/db.'
}
$db1Host = $matches[1]
$db1Port = $matches[2]
$db1DefaultDb = $matches[3]
if (-not $AllowRemote -and $db1Host -notin @('localhost', '127.0.0.1')) {
    throw "Хост '$db1Host' не локальный. Для рабочей БД передайте -AllowRemote явно."
}
if ($Databases.Count -eq 0) { $Databases = @($db1DefaultDb) }

$db1Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $OutputRoot) { $OutputRoot = Join-Path $db1Root "outputs/db1-udr-backup-$db1Stamp" }
New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
$OutputRoot = (Resolve-Path -LiteralPath $OutputRoot).Path

$db1Psql = Join-Path $PostgresBin 'psql.exe'
$db1Dump = Join-Path $PostgresBin 'pg_dump.exe'
$db1Create = Join-Path $PostgresBin 'createdb.exe'
$db1Drop = Join-Path $PostgresBin 'dropdb.exe'
foreach ($db1Tool in @($db1Psql, $db1Dump, $db1Create, $db1Drop)) {
    if (-not (Test-Path -LiteralPath $db1Tool)) { throw "Не найден PostgreSQL tool: $db1Tool" }
}

$db1User = $db1Settings['spring.datasource.username']
$db1Password = $db1Settings['spring.datasource.password']
$db1Tables = @('report_template', 'report_band', 'report_field', 'report_param', 'report_order')
$db1TableOrder = "array['report_template','report_band','report_field','report_param','report_order']"
$db1CountsSql = @"
select t.table_name,
       (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from public.%I', t.table_name), false, true, '')))[1]::text::bigint
  from information_schema.tables t
 where t.table_schema = 'public'
   and t.table_name in ('report_template','report_band','report_field','report_param','report_order')
 order by array_position($db1TableOrder, t.table_name);
"@

function Invoke-Db1Psql {
    param([string]$Database, [string[]]$Arguments)
    & $db1Psql '-h' $db1Host '-p' $db1Port '-U' $db1User '-d' $Database '-X' '-v' 'ON_ERROR_STOP=1' @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "psql завершился с кодом $LASTEXITCODE (db: $Database)."
    }
}

function Get-Db1Counts {
    param([string]$Database)
    $lines = & $db1Psql '-h' $db1Host '-p' $db1Port '-U' $db1User '-d' $Database '-X' '-A' '-t' '-F' '|' '-v' 'ON_ERROR_STOP=1' '-c' $db1CountsSql
    if ($LASTEXITCODE -ne 0) { throw "Не удалось снять counts (db: $Database)." }
    $result = @{}
    foreach ($line in @($lines)) {
        if ($line -match '^([a-z_]+)\|(\d+)$') { $result[$matches[1]] = [int64]$matches[2] }
    }
    return $result
}

$db1SavedPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
$db1Manifest = New-Object System.Collections.Generic.List[string]
$db1Manifest.Add("DB1 UDR backup, $db1Stamp")
$db1Manifest.Add("host=$db1Host port=$db1Port user=$db1User")
$db1Files = New-Object System.Collections.Generic.List[string]
try {
    $env:PGPASSWORD = $db1Password
    foreach ($db1Db in $Databases) {
        if ($db1Db -notmatch '^[A-Za-z0-9_]+$') { throw "Некорректное имя БД: $db1Db" }
        Write-Host "== $db1Db"
        $db1Counts = Get-Db1Counts -Database $db1Db
        if ($db1Counts.Count -eq 0) {
            $skip = Join-Path $OutputRoot "$db1Db.SKIPPED.txt"
            "В БД $db1Db нет UDR-таблиц: удалять нечего." | Set-Content -LiteralPath $skip -Encoding UTF8
            $db1Files.Add($skip)
            $db1Manifest.Add("${db1Db}: UDR tables absent, skipped")
            continue
        }

        $countsFile = Join-Path $OutputRoot "$db1Db.udr-counts.txt"
        $db1Counts.GetEnumerator() | Sort-Object Key | ForEach-Object { "$($_.Key)|$($_.Value)" } |
            Set-Content -LiteralPath $countsFile -Encoding UTF8
        $db1Files.Add($countsFile)

        $dumpFile = Join-Path $OutputRoot "$db1Db.udr.sql"
        $dumpArgs = @('-h', $db1Host, '-p', $db1Port, '-U', $db1User, '-d', $db1Db,
                      '--no-owner', '--no-privileges')
        foreach ($db1Table in $db1Tables) {
            if ($db1Counts.ContainsKey($db1Table)) { $dumpArgs += @('-t', "public.$db1Table") }
        }
        $dumpArgs += @('-f', $dumpFile)
        & $db1Dump @dumpArgs
        if ($LASTEXITCODE -ne 0) { throw "pg_dump завершился с кодом $LASTEXITCODE (db: $db1Db)." }
        $db1Files.Add($dumpFile)

        $grantsFile = Join-Path $OutputRoot "$db1Db.udr-grants.csv"
        $grantsPath = $grantsFile.Replace('\', '/')
        $grantsExists = (& $db1Psql '-h' $db1Host '-p' $db1Port '-U' $db1User '-d' $db1Db '-X' '-A' '-t' `
                '-v' 'ON_ERROR_STOP=1' '-c' "select to_regclass('public.access_grant') is not null").Trim()
        if ($grantsExists -eq 't') {
            Invoke-Db1Psql -Database $db1Db -Arguments @('-c',
                "\copy (select dimension, subject_type, subject_key, dimension_value_id, can_read from access_grant where dimension ilike '%udr%' or dimension ilike '%reportstudio%' or dimension ilike '%report_template%' order by dimension, subject_type, subject_key) to '$grantsPath' with (format csv, header true)")
        }
        else {
            'access_grant table is absent' | Set-Content -LiteralPath $grantsFile -Encoding UTF8
        }
        $db1Files.Add($grantsFile)

        $restoreStatus = 'skipped'
        if (-not $SkipRestoreCheck) {
            $checkDb = 'db1_udr_restore_check_' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
            Write-Host "   restore check -> $checkDb"
            & $db1Create '-h' $db1Host '-p' $db1Port '-U' $db1User $checkDb
            if ($LASTEXITCODE -ne 0) { throw "Не удалось создать проверочную БД $checkDb." }
            try {
                Invoke-Db1Psql -Database $checkDb -Arguments @('-f', $dumpFile)
                $restored = Get-Db1Counts -Database $checkDb
                foreach ($db1Table in $db1Counts.Keys) {
                    $expected = $db1Counts[$db1Table]
                    $actual = if ($restored.ContainsKey($db1Table)) { $restored[$db1Table] } else { -1 }
                    if ($actual -ne $expected) {
                        throw "Восстановление разошлось: $db1Db.$db1Table ожидалось $expected, получено $actual."
                    }
                }
                $restoreStatus = "ok ($checkDb)"
            }
            catch {
                Write-Host "   проверочная БД сохранена для диагностики: $checkDb"
                throw
            }
            & $db1Drop '-h' $db1Host '-p' $db1Port '-U' $db1User $checkDb
            if ($LASTEXITCODE -ne 0) { throw "Не удалось удалить проверочную БД $checkDb." }
        }

        $db1Manifest.Add("${db1Db}: " + (($db1Counts.GetEnumerator() | Sort-Object Key | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', '))
        $db1Manifest.Add("${db1Db}: restore_check=$restoreStatus")
    }

    $db1Manifest.Add('')
    $db1Manifest.Add('SHA256:')
    foreach ($db1File in $db1Files) {
        $hash = (Get-FileHash -LiteralPath $db1File -Algorithm SHA256).Hash.ToLowerInvariant()
        $db1Manifest.Add("$hash  $([System.IO.Path]::GetFileName($db1File))")
    }
    $manifestFile = Join-Path $OutputRoot 'SHA256SUMS.txt'
    $db1Manifest | Set-Content -LiteralPath $manifestFile -Encoding UTF8
    Write-Host ''
    Write-Host "Бэкап и проверка завершены: $OutputRoot"
    Write-Host "Манифест: $manifestFile"
}
finally {
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $db1SavedPassword, 'Process')
}
