[CmdletBinding()]
param(
    [ValidateSet('Prepare','Start','Stop','Status')][string]$Action = 'Prepare',
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [int]$Port = 8094
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$registryRoot = Split-Path $PSScriptRoot -Parent
$registryOutput = Join-Path $registryRoot 'outputs/entity-registry-r2'
Push-Location -LiteralPath $registryRoot
try {
    $stateFile = Join-Path $registryOutput 'server-state.json'
    if ($Action -in @('Stop','Status')) {
        if (!(Test-Path $stateFile)) { Write-Output 'R2 stand is not started'; return }
        $state = Get-Content $stateFile -Raw | ConvertFrom-Json
        $owned = Get-Process -Id $state.pid -ErrorAction SilentlyContinue
        if ($owned -and ($owned.Path -ne $state.path -or $owned.StartTime.ToUniversalTime().Ticks -ne $state.startTicks)) {
            throw 'Stored PID no longer belongs to this stand'
        }
        if ($Action -eq 'Stop' -and $owned) { Stop-Process -Id $owned.Id }
        Write-Output "R2 stand running: $([bool]$owned -and $Action -ne 'Stop')"
        return
    }
    if (!(Test-Path "$registryOutput/classpath.txt")) { throw 'Run scripts/test-entity-registry-platform.ps1 first' }
    $registryClasspath = 'outputs/entity-registry-r2/classes;platform-vaadin/src/main/resources;' + (Get-Content "$registryOutput/classpath.txt" -Raw).Trim()
    if ($Action -eq 'Prepare') {
        $registryArguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$registryClasspath.Replace('\','/')+'"'),'-d','outputs/entity-registry-r2/classes',
            'platform-vaadin/src/test/java/org/ipro/vaadin/explorer/RegistryFixture.java')
        $registryArguments += Get-ChildItem scripts/entity-registry-r2 -Filter '*.java' | ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'}
        [IO.File]::WriteAllLines("$registryOutput/stand-compile.args",[string[]]$registryArguments)
        & "$JavaHome/bin/javac.exe" ('@'+"$registryOutput/stand-compile.args") *> "$registryOutput/stand-compile.log"
        if ($LASTEXITCODE) { throw 'Stand compilation failed; see stand-compile.log' }
        Write-Output 'Database-free R2 stand prepared'
        return
    }
    if (!(Test-Path "$registryOutput/classes/org/ipro/registryfixture/RegistryBrowser.class")) { throw 'Prepare the stand first' }
    $registryExcluded = @(Get-ChildItem -Path 'platform-*/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports' |
        ForEach-Object { Get-Content $_.FullName | Where-Object { $_ -match '^org\.ipro\.' } })
    $registryArguments = @('-Dfile.encoding=UTF-8','-cp',('"'+$registryClasspath.Replace('\','/')+'"'),'org.ipro.registryfixture.RegistryBrowser',
        '--server.address=127.0.0.1',"--server.port=$Port",'--spring.main.banner-mode=off','--vaadin.launch-browser=false',
        ('--spring.autoconfigure.exclude='+($registryExcluded -join ',')),
        '--spring.config.location=optional:file:outputs/entity-registry-r2/no-application-config.properties',
        '--vaadin.allowed-packages=org.ipro.registryfixture,org.ipro.vaadin.explorer.ui,com.vaadin,org.ipro.filtergrid','--logging.level.org.atmosphere=warn')
    [IO.File]::WriteAllLines("$registryOutput/server.args",[string[]]$registryArguments)
    $registryProcess = Start-Process -FilePath "$JavaHome/bin/java.exe" -ArgumentList ('@"'+"$registryOutput/server.args"+'"') `
        -WorkingDirectory $registryRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput "$registryOutput/server.log" -RedirectStandardError "$registryOutput/server.err.log"
    @{pid=$registryProcess.Id; path=$registryProcess.Path; startTicks=$registryProcess.StartTime.ToUniversalTime().Ticks; url="http://127.0.0.1:$Port"} |
        ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding utf8
    Write-Output "R2 stand starting at http://127.0.0.1:$Port; see outputs/entity-registry-r2/server.log"
} finally { Pop-Location }
