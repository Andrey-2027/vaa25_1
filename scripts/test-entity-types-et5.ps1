[CmdletBinding()]
param(
    [ValidateSet('Build','Start','Stop')][string]$Action = 'Build',
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$Receipt,
    [string]$IsolationRoot = [IO.Path]::GetTempPath(),
    [switch]$GrantDenied,
    [int]$Port = 8095,
    [switch]$Offline
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$output = Join-Path $root 'outputs/et5'
New-Item -ItemType Directory -Force $output | Out-Null
if ($Action -eq 'Build') {
    $runId = [guid]::NewGuid().ToString('N')
    $isolated = [IO.Path]::GetFullPath((Join-Path $IsolationRoot "entity-types-et5-$runId"))
    if ($isolated.StartsWith($root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The external consumer must be built outside the checkout.'
    }
    New-Item -ItemType Directory $isolated | Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'examples/entity-types-consumer/pom.xml') -Destination $isolated
    Copy-Item -LiteralPath (Join-Path $root 'examples/entity-types-consumer/src') -Destination $isolated -Recurse
    $Receipt = Join-Path $output "$runId.json"
    $state = [ordered]@{ isolated = $isolated; built = $false; jar = ''; processId = 0; startTicks = 0; port = $Port }
    $state | ConvertTo-Json | Set-Content -LiteralPath $Receipt -Encoding utf8
    $previousJava = $env:JAVA_HOME
    try {
        $env:JAVA_HOME = $JavaHome
        $arguments = @('-f', (Join-Path $isolated 'pom.xml'), '-Pbootstrap-smoke,production', 'package')
        if ($Offline) { $arguments = @('-o') + $arguments }
        & $Maven @arguments *> (Join-Path $output "$runId-build.log")
        if ($LASTEXITCODE -ne 0) { throw "External consumer build failed; see $runId-build.log" }
        Copy-Item -LiteralPath (Join-Path $isolated 'target/surefire-reports') -Destination (Join-Path $output "$runId-tests") -Recurse
        $state.jar = Join-Path $isolated 'target/entity-types-consumer-exec.jar'
        $state.built = $true
        $state['sha256'] = (Get-FileHash -LiteralPath $state.jar -Algorithm SHA256).Hash
        $state | ConvertTo-Json | Set-Content -LiteralPath $Receipt -Encoding utf8
    } finally { $env:JAVA_HOME = $previousJava }
    Write-Output "External binary-only consumer built. Receipt: $Receipt"
    return
}
if (!$Receipt) { throw 'Pass the receipt produced by Build.' }
$state = Get-Content -LiteralPath $Receipt -Raw | ConvertFrom-Json
if ($Action -eq 'Stop') {
    if ($state.processId -eq 0) { return }
    $process = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
    if ($process) {
        if ($process.StartTime.ToUniversalTime().Ticks -ne $state.startTicks -or
            $process.Path -ne (Join-Path $JavaHome 'bin/java.exe')) { throw 'Process identity changed; refusing to stop it.' }
        Stop-Process -InputObject $process
    }
    $state.processId = 0
    $state | ConvertTo-Json | Set-Content -LiteralPath $Receipt -Encoding utf8
    return
}
if (!$state.built) { throw 'Build did not finish successfully.' }
if ($state.processId -ne 0 -and (Get-Process -Id $state.processId -ErrorAction SilentlyContinue)) { throw 'Stop the existing owned server first.' }
if ((Get-FileHash -LiteralPath $state.jar -Algorithm SHA256).Hash -ne $state.sha256) { throw 'Packaged artifact hash changed.' }
$logPrefix = [IO.Path]::ChangeExtension($Receipt, $(if ($GrantDenied) { 'granted' } else { 'browser' }))
$db = (Join-Path $state.isolated 'browser-db').Replace('\','/')
$arguments = @('-jar', ('"'+$state.jar+'"'), '--vaadin.productionMode=true', "--server.port=$Port",
    "--spring.datasource.url=jdbc:h2:file:$db;DB_CLOSE_ON_EXIT=FALSE", '--spring.jpa.hibernate.ddl-auto=update',
    ('--et5.grant-denied='+$GrantDenied.IsPresent.ToString().ToLowerInvariant()))
$process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList $arguments `
    -WorkingDirectory $state.isolated -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput "$logPrefix.stdout.log" -RedirectStandardError "$logPrefix.stderr.log"
$state.processId = $process.Id; $state.startTicks = $process.StartTime.ToUniversalTime().Ticks; $state.port = $Port
$state | ConvertTo-Json | Set-Content -LiteralPath $Receipt -Encoding utf8
Write-Output "Started owned ET5 server pid=$($process.Id), http://127.0.0.1:$Port/ . Read $logPrefix.stdout.log for readiness."
