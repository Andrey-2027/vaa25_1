[CmdletBinding()]
param(
    [ValidateSet('Prepare','Restart','Stop','Cleanup')][string]$Action = 'Prepare',
    [string]$RunDirectory,
    [string]$EvidenceDirectory,
    [string]$LocalRepository,
    [string]$LocalConfig,
    [string]$PostgresBin = 'C:/Program Files/PostgreSQL/17/bin',
    [string]$JavaHome = 'C:/Program Files/Axiom/AxiomJDK-21',
    [string]$MavenCommand = 'C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd',
    [switch]$Offline
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $LocalRepository) { $LocalRepository = Join-Path $root 'outputs/ig1/m2' }
if (-not $LocalConfig) { $LocalConfig = Join-Path $root 'config/application-local.properties' }
$settings = @{}
Get-Content -LiteralPath $LocalConfig | ForEach-Object {
    if ($_ -match '^([^#=]+)=(.*)$') { $settings[$matches[1].Trim()] = $matches[2].Trim() }
}
if ($settings['spring.datasource.url'] -notmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):(\d+)/([A-Za-z0-9_]+)$') {
    throw 'Only an explicitly configured local PostgreSQL server is supported.'
}
$dbHost = $matches[1]; $dbPort = $matches[2]
$savedEnvironment = @{}
foreach ($name in @('PGPASSWORD','JAVA_HOME','CONSUMER_DB_USERNAME','CONSUMER_DB_PASSWORD',
        'CONSUMER_TEST_JDBC_URL','CONSUMER_TEST_AGGREGATE_JDBC_URL','CONSUMER_JDBC_URL',
        'CONSUMER_FIXTURES_ENABLED')) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Sql([string]$Database, [string]$Query) {
    $result = & (Join-Path $PostgresBin 'psql.exe') -h $dbHost -p $dbPort `
        -U $settings['spring.datasource.username'] -d $Database -X -v ON_ERROR_STOP=1 -tAc $Query 2>&1
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL operation failed for $Database : $result" }
    return ($result | Out-String).Trim()
}
function Save-State { $script:state | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $RunDirectory 'state.json') -Encoding utf8 }
function Assert-Ownership {
    if ($state.runId -notmatch '^[a-f0-9]{32}$' -or $state.owner -ne 'platform-consumer-p4') { throw 'Unknown run ownership' }
    foreach ($db in @($state.readDatabase,$state.aggregateDatabase,$state.webDatabase)) {
        if ($db -notmatch ('^consumer_p4_' + $state.runId + '_(read|aggregate|web)$')) { throw 'Database is not owned by this run' }
    }
    if ($state.host -ne $dbHost -or $state.port -ne $dbPort) { throw 'Run/server mismatch' }
}
function Stop-OwnedServer {
    if ($state.processId -le 0) { return }
    $process = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
    if ($process) {
        $details = Get-CimInstance Win32_Process -Filter "ProcessId=$($state.processId)"
        if ($process.ProcessName -ne 'java' -or $details.CommandLine -notlike ('*' + $state.jar + '*') `
                -or $process.StartTime.ToUniversalTime().Ticks -ne ([datetime]$state.processStartedUtc).ToUniversalTime().Ticks) {
            throw 'Refusing to stop a process that no longer matches the owned Java server.'
        }
        Stop-Process -Id $process.Id
        $process.WaitForExit(10000) | Out-Null
    }
    $state.processId = 0; Save-State
}
function Start-OwnedServer([string]$DdlMode, [string]$Label) {
    if (-not $env:CONSUMER_DEMO_PASSWORD) { throw 'Set CONSUMER_DEMO_PASSWORD for the local fixture accounts.' }
    $env:CONSUMER_JDBC_URL = "jdbc:postgresql://${dbHost}:${dbPort}/$($state.webDatabase)"
    $env:CONSUMER_FIXTURES_ENABLED = 'true'
    $stdout = Join-Path $RunDirectory "$Label.stdout.log"
    $stderr = Join-Path $RunDirectory "$Label.stderr.log"
    $arguments = @('-jar', ('"' + $state.jar + '"'), '--server.port=0', '--vaadin.productionMode=true',
        "--spring.jpa.hibernate.ddl-auto=$DdlMode", '--spring.main.banner-mode=off')
    $process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList $arguments `
        -WorkingDirectory (Split-Path -Parent $state.jar) -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $state.processId = $process.Id
    $state.processStartedUtc = $process.StartTime.ToUniversalTime().ToString('o')
    Save-State
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    do {
        Start-Sleep -Milliseconds 200
        $log = if (Test-Path $stdout) { Get-Content $stdout -Raw } else { '' }
        if ($log -match 'Started ConsumerApplication' -and $log -match 'Tomcat started on port (\d+)') {
            $state.url = "http://127.0.0.1:$($matches[1])"
            $response = Invoke-WebRequest ($state.url + '/login') -UseBasicParsing -TimeoutSec 15
            if ($response.StatusCode -ne 200 -or $response.Content -notmatch 'VAADIN') { throw 'Missing packaged Vaadin bootstrap' }
            Save-State; return
        }
        if ($process.HasExited) { throw "Packaged server failed. Inspect $stdout and $stderr" }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw 'Packaged server startup timed out; retained state identifies its owned process.'
}
function Snapshot([string]$Label) {
    $query = @'
select json_build_object(
 'materials', (select coalesce(json_agg(t order by id),'[]'::json) from (select id,version,code,name from consumer_material) t),
 'requests', (select coalesce(json_agg(t order by id),'[]'::json) from (select id,version,number,date,department_id,status,comment from consumer_material_request) t),
 'rows', (select coalesce(json_agg(t order by id),'[]'::json) from (select id,version,request_id,line_number,material_id,quantity from consumer_material_request_line) t),
 'grants', (select coalesce(json_agg(t order by id),'[]'::json) from access_grant t),
 'numbering', (select coalesce(json_agg(t order by numbering_key),'[]'::json) from numbering_counter t)
)::text
'@
    Sql $state.webDatabase $query | Set-Content (Join-Path $RunDirectory "$Label.json") -Encoding utf8
}

try {
    $env:PGPASSWORD = $settings['spring.datasource.password']
    $env:CONSUMER_DB_USERNAME = $settings['spring.datasource.username']
    $env:CONSUMER_DB_PASSWORD = $settings['spring.datasource.password']
    $env:JAVA_HOME = $JavaHome
    if ($Action -eq 'Prepare') {
        if (-not $env:CONSUMER_DEMO_PASSWORD) { throw 'Set CONSUMER_DEMO_PASSWORD for the local fixture accounts.' }
        if ($RunDirectory) { throw 'Prepare always creates a new owned run directory.' }
        $runId = [Guid]::NewGuid().ToString('N')
        $RunDirectory = Join-Path $root "examples/platform-consumer/target/p4-evidence/$runId"
        if ($EvidenceDirectory) { $RunDirectory = [IO.Path]::GetFullPath($EvidenceDirectory) }
        if (Test-Path -LiteralPath $RunDirectory) { throw 'Evidence directory must be new for this run' }
        New-Item -ItemType Directory -Path $RunDirectory -Force | Out-Null
        $isolated = Join-Path ([IO.Path]::GetTempPath()) "ipro-consumer-p4-$runId"
        $consumer = Join-Path $isolated 'consumer'
        $runtime = Join-Path $isolated 'runtime'
        New-Item -ItemType Directory -Path $consumer,$runtime -Force | Out-Null
        Copy-Item (Join-Path $root 'examples/platform-consumer/pom.xml') $consumer
        Copy-Item (Join-Path $root 'examples/platform-consumer/src') $consumer -Recurse
        $script:state = [pscustomobject][ordered]@{
            owner='platform-consumer-p4'; runId=$runId; host=$dbHost; port=$dbPort
            readDatabase="consumer_p4_${runId}_read"; aggregateDatabase="consumer_p4_${runId}_aggregate"
            webDatabase="consumer_p4_${runId}_web"; jar=(Join-Path $runtime 'platform-consumer-exec.jar')
            processId=0; processStartedUtc=''; url=''; localRepository=[IO.Path]::GetFullPath($LocalRepository)
            status='preparing'; postgresVersion=(Sql 'postgres' 'select version()')
        }
        Save-State; Assert-Ownership
        foreach ($db in @($state.readDatabase,$state.aggregateDatabase,$state.webDatabase)) { [void](Sql 'postgres' "CREATE DATABASE $db") }
        $env:CONSUMER_TEST_JDBC_URL = "jdbc:postgresql://${dbHost}:${dbPort}/$($state.readDatabase)"
        $env:CONSUMER_TEST_AGGREGATE_JDBC_URL = "jdbc:postgresql://${dbHost}:${dbPort}/$($state.aggregateDatabase)"
        $arguments = @('-f',(Join-Path $consumer 'pom.xml'),"-Dmaven.repo.local=$($state.localRepository)",
            '-Pproduction,bootstrap-smoke','-Dconsumer.test.database=postgres','clean','verify')
        if ($Offline) { $arguments = @('-o') + $arguments }
        Push-Location $consumer
        try { & $MavenCommand @arguments *> (Join-Path $RunDirectory 'build.log'); $buildCode = $LASTEXITCODE }
        finally { Pop-Location }
        Copy-Item (Join-Path $consumer 'target/surefire-reports') $RunDirectory -Recurse -ErrorAction SilentlyContinue
        if ($buildCode -ne 0) { throw "PostgreSQL consumer build failed; see $RunDirectory/build.log" }
        . (Join-Path $PSScriptRoot 'platform-consumer-gate-lib.ps1')
        $cases = @(Assert-ConsumerJUnit (Join-Path $RunDirectory 'surefire-reports'))
        Copy-Item (Join-Path $consumer 'target/platform-consumer-exec.jar') $state.jar
        Copy-Item $state.jar $RunDirectory
        Copy-Item (Join-Path $consumer 'target/consumer-runtime-dependency-tree.json') $RunDirectory
        & (Join-Path $PSScriptRoot 'test-platform-consumer-package.ps1') -Jar $state.jar `
            -LocalRepository $state.localRepository -OutputDirectory $RunDirectory
        [ordered]@{tests=$cases; database='PostgreSQL'; isolatedConsumer=$consumer; localRepository=$state.localRepository;
            javaHome=$JavaHome; mavenCommand=$MavenCommand;
            pomSha256=(Get-FileHash (Join-Path $consumer 'pom.xml')).Hash;
            jarSha256=(Get-FileHash $state.jar -Algorithm SHA256).Hash;
            sources=@(Get-ChildItem (Join-Path $root 'examples/platform-consumer/src') -File -Recurse | ForEach-Object {
                [ordered]@{path=[IO.Path]::GetRelativePath((Join-Path $root 'examples/platform-consumer'),$_.FullName); sha256=(Get-FileHash $_.FullName).Hash}
            })} | ConvertTo-Json -Depth 7 | Set-Content (Join-Path $RunDirectory 'build-receipt.json') -Encoding utf8
        Start-OwnedServer 'create' 'first-start'
        $state.status='browser-ready'; Save-State
    } else {
        if (-not $RunDirectory) { throw 'Pass the existing owned -RunDirectory' }
        $RunDirectory = (Resolve-Path -LiteralPath $RunDirectory).Path
        $script:state = Get-Content (Join-Path $RunDirectory 'state.json') -Raw | ConvertFrom-Json
        Assert-Ownership
        switch ($Action) {
            'Restart' {
                Snapshot 'before-restart'; Stop-OwnedServer
                Start-OwnedServer 'validate' 'restart'
                Snapshot 'after-restart'
                if ((Get-Content (Join-Path $RunDirectory 'before-restart.json') -Raw) -ne
                        (Get-Content (Join-Path $RunDirectory 'after-restart.json') -Raw)) { throw 'Business/numbering/grant state changed on restart' }
                $state.status='restart-pass-browser-ready'; Save-State
            }
            'Stop' { Stop-OwnedServer; $state.status='stopped'; Save-State }
            'Cleanup' {
                Stop-OwnedServer
                foreach ($db in @($state.readDatabase,$state.aggregateDatabase,$state.webDatabase)) {
                    [void](Sql 'postgres' "DROP DATABASE IF EXISTS $db")
                }
                $state.status='cleaned'; Save-State
            }
        }
    }
    Write-Host "P4 $Action : $($state.status); URL=$($state.url); evidence=$RunDirectory"
} finally {
    foreach ($name in $savedEnvironment.Keys) { [Environment]::SetEnvironmentVariable($name,$savedEnvironment[$name],'Process') }
}

