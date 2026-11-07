#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('Start','Stop','Status')][string]$Action='Status',
    [Parameter(Mandatory)][string]$ProjectRoot,
    [Parameter(Mandatory)][string]$StateDirectory,
    [Parameter(Mandatory)][ValidateSet('editor','consumer')][string]$Kind,
    [string]$JavaHome='C:/Program Files/Axiom/AxiomJDK-21',
    [int]$Port=8097,
    [switch]$Initialize,
    [switch]$AllowSourceWrite,
    [switch]$DisablePilot,
    [switch]$NoSourceRoot,
    [switch]$PrepareEm6
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$ProjectRoot=[IO.Path]::GetFullPath($ProjectRoot)
$StateDirectory=[IO.Path]::GetFullPath($StateDirectory)
if ($ProjectRoot.StartsWith($repo,[StringComparison]::OrdinalIgnoreCase) -or $StateDirectory.StartsWith($repo,[StringComparison]::OrdinalIgnoreCase)) {
    throw 'Stand project and persistent data must be outside the original checkout'
}
New-Item -ItemType Directory $StateDirectory -Force | Out-Null
$stateFile=Join-Path $StateDirectory 'server-state.json'
if (Test-Path $stateFile) {
    $previous=Get-Content $stateFile -Raw | ConvertFrom-Json
    $process=Get-Process -Id $previous.pid -ErrorAction SilentlyContinue
    if ($process) {
        $executable=$process.Path
        if (!$executable) { $executable=(Get-CimInstance Win32_Process -Filter "ProcessId=$($previous.pid)").ExecutablePath }
        $expectedExecutable=if($previous.executable){$previous.executable}else{[IO.Path]::GetFullPath("$JavaHome/bin/java.exe")}
        if (!$executable -or ![StringComparer]::OrdinalIgnoreCase.Equals($executable.Replace('/','\'),$expectedExecutable.Replace('/','\')) -or $process.StartTime.ToUniversalTime().Ticks -ne $previous.startTicks) { throw 'PID identity mismatch' }
    }
} else { $process=$null }
if ($Action -eq 'Status') { Write-Output "Running: $([bool]$process)"; return }
if ($Action -eq 'Stop') { if ($process) { Stop-Process -Id $process.Id }; Write-Output 'Stopped'; return }
if ($process) { throw 'Stand is already running' }
if (Test-Path $stateFile) {
    $history=Join-Path $StateDirectory "history/$($previous.startTicks)"
    New-Item -ItemType Directory $history -Force | Out-Null
    foreach ($file in @('server-state.json','server.args','stand.properties','server.log','server.err.log')) {
        if (Test-Path "$StateDirectory/$file") { Copy-Item -LiteralPath "$StateDirectory/$file" -Destination $history }
    }
}
$db=Join-Path $StateDirectory 'database'
if ($Initialize -and (Test-Path "$db.mv.db")) { throw 'Refusing schema creation on an existing H2 database' }
if (!$Initialize -and !(Test-Path "$db.mv.db")) { throw 'No saved database; explicitly initialize a fresh stand first' }
if (!(Test-Path "$ProjectRoot/classpath.txt")) { throw 'Build classpath.txt before starting' }
$props=@(
    "server.address=127.0.0.1", "server.port=$Port", "server.servlet.session.cookie.name=acceptance-$Port",
    "spring.datasource.url=jdbc:h2:file:$($db.Replace('\','/'));DB_CLOSE_ON_EXIT=FALSE",
    'spring.datasource.driver-class-name=org.h2.Driver', 'spring.datasource.username=sa','spring.datasource.password=',
    'spring.jpa.database-platform=org.hibernate.dialect.H2Dialect',
    "spring.jpa.hibernate.ddl-auto=$(if($Initialize){'create'}else{'validate'})",
    'spring.devtools.restart.enabled=false','vaadin.launch-browser=false','ipro.telemetry.enabled=false',
    'ipro.telemetry.db-journal=false','logging.level.org.atmosphere=warn'
)
if ($Kind -eq 'consumer') {
    $props+=@('spring.profiles.active=acceptance-ui','spring.main.web-application-type=servlet',"acceptance.initialize=$($Initialize.IsPresent.ToString().ToLowerInvariant())")
    $main='com.example.generated.harness.GenerationHarnessApplication'
} else {
    $props+=@('spring.profiles.active=demo','app.demo-data.enabled=true',"app.entity-explorer.ux-pilot.enabled=$((!$DisablePilot.IsPresent).ToString().ToLowerInvariant())",
        "app.entity-draft.source-root=$(if($NoSourceRoot){''}else{$ProjectRoot.Replace('\','/')+'/src/main/java'})",
        "app.entity-draft.allow-source-write=$($AllowSourceWrite.IsPresent.ToString().ToLowerInvariant())",'app.entity-draft.default-package=com.example.generated',
        "acceptance.em6.prepare=$($PrepareEm6.IsPresent.ToString().ToLowerInvariant())")
    $main='org.ip.Application'
}
[IO.File]::WriteAllText("$StateDirectory/stand.properties",($props -join "`n"),[Text.UTF8Encoding]::new($false))
$cp="$ProjectRoot/target/classes;"+[IO.File]::ReadAllText("$ProjectRoot/classpath.txt").Trim()
$arguments=@('-Dfile.encoding=UTF-8','-Dspring.devtools.restart.enabled=false',"-Dacceptance.evidence=$($StateDirectory.Replace('\','/'))/download-evidence",'-cp',$cp,$main,
    "--spring.config.additional-location=file:$($StateDirectory.Replace('\','/'))/stand.properties")
$quoted=@($arguments | ForEach-Object { '"'+$_.Replace('\','/').Replace('"','\"')+'"' })
[IO.File]::WriteAllLines("$StateDirectory/server.args",$quoted,[Text.UTF8Encoding]::new($false))
$started=Start-Process "$JavaHome/bin/java.exe" -ArgumentList ('@"'+"$StateDirectory/server.args"+'"') -WorkingDirectory $ProjectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$StateDirectory/server.log" -RedirectStandardError "$StateDirectory/server.err.log"
@{pid=$started.Id;executable=[IO.Path]::GetFullPath("$JavaHome/bin/java.exe");startTicks=$started.StartTime.ToUniversalTime().Ticks;url="http://127.0.0.1:$Port";project=$ProjectRoot;database=$db;initialize=$Initialize.IsPresent;kind=$Kind} | ConvertTo-Json | Set-Content $stateFile -Encoding utf8
Write-Output "Started $Kind PID=$($started.Id) http://127.0.0.1:$Port"
