[CmdletBinding()]
param(
    [ValidateSet('Prepare','Test','Start','Stop','Status')][string]$Action = 'Test',
    [ValidateRange(1024,65535)][int]$Port = 8089,
    [switch]$DensityFixture,
    [switch]$Legacy,
    [switch]$Sources,
    [switch]$HotDeploy,
    [switch]$CompiledApplication,
    [string]$RuntimeClasspath = 'outputs/entity-explorer-p8-2/runtime-classpath.txt',
    [string]$CompiledClasses = 'target/classes',
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$PilotProperties = 'config/navigation-pilot.properties.local'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pilotRoot = Split-Path $PSScriptRoot -Parent
$pilotOutput = Join-Path $pilotRoot 'outputs/entity-explorer-p2'
$stateFile = Join-Path $pilotOutput "server-$Port-state.json"
Push-Location -LiteralPath $pilotRoot
try {
    New-Item -ItemType Directory -Force -Path "$pilotOutput/classes","$pilotOutput/test-classes","$pilotOutput/tmp" | Out-Null
    if ($Action -in @('Status','Stop')) {
        $owned = $null
        if (Test-Path $stateFile) {
            $state = Get-Content $stateFile -Raw | ConvertFrom-Json
            $owned = Get-Process -Id $state.processId -ErrorAction SilentlyContinue
            if ($owned -and ($owned.Path -ne $state.executable -or $owned.StartTime.ToUniversalTime().Ticks -ne $state.startTimeUtcTicks)) {
                throw 'Stored PID does not belong to this stand.'
            }
        }
        if ($Action -eq 'Stop' -and $owned) { Stop-Process -Id $owned.Id }
        Write-Output "Stand running: $([bool]$owned -and $Action -ne 'Stop')"
        return
    }
    if (!(Test-Path 'outputs/entity-explorer-p1/local-classpath.txt')) { throw 'Prepare P1 dependencies with scripts/test-entity-explorer-structure.ps1 first.' }
    $dependencies = (Get-Content outputs/entity-explorer-p1/local-classpath.txt -Raw).Trim()
    if ($Action -eq 'Prepare') {
        $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$dependencies.Replace('\','/')+'"'),
            '-d','outputs/entity-explorer-p2/classes','platform-core/src/main/java/org/ipro/metadata/FieldMetadataInfo.java')
        $arguments += @('platform-vaadin/src/main/java/org/ipro/form/builder/ItemFormVariants.java',
            'platform-vaadin/src/main/java/org/ipro/form/builder/ItemFormCustomizationRegistrar.java',
            'platform-vaadin/src/main/java/org/ipro/form/registry/FormRegistry.java')
        $arguments += Get-ChildItem platform-vaadin/src/main/java/org/ipro/vaadin/explorer -Recurse -Filter '*.java' |
            ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
        $arguments += Get-ChildItem src/main/java -Recurse -Filter '*.java' |
            ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
        [IO.File]::WriteAllLines("$pilotOutput/prepare.args",[string[]]$arguments,[Text.UTF8Encoding]::new($false))
        & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$pilotOutput/prepare.args") *> "$pilotOutput/prepare.log"
        if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; see prepare.log.' }
        Write-Output 'Compact pilot compiled. Next: -Action Test or -Action Start.'
        return
    }
    $classpath = 'outputs/entity-explorer-p2/classes;src/main/resources;' + $dependencies
    if ($CompiledApplication) {
        if (!(Test-Path (Join-Path $CompiledClasses 'org/ip/views/admin/EntityDraftEditorView.class')) -or
            !(Test-Path $RuntimeClasspath)) {
            throw 'Run scripts/test-entity-draft-editor.ps1 first to build the application and resolve its current runtime dependencies.'
        }
        $classpath = $CompiledClasses + ';src/main/resources;' + (Get-Content $RuntimeClasspath -Raw).Trim()
    }
    if ($Action -eq 'Test') {
        $classpath += ';src/test/resources;outputs/entity-explorer-p1/junit-launcher.jar'
        $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
            '-sourcepath','src/test/java','-d','outputs/entity-explorer-p2/test-classes',
            'scripts/entity-explorer-pilot/ExplorerStructureChecks.java',
            'src/test/java/org/ip/views/admin/EntityExplorerPilotPanelTest.java',
            'src/test/java/org/ip/views/admin/ExplorerSourceReaderTest.java',
            'platform-vaadin/src/test/java/org/ipro/form/builder/ItemFormClassDescriptionTest.java',
            'src/test/java/org/ip/vaadin/explorer/EntitySummaryAssemblerTest.java',
            'src/test/java/org/ip/form/registry/FormRegistryDuplicateTest.java',
            'src/test/java/org/ip/form/registry/FormRegistryLifecycleTest.java',
            'platform-vaadin/src/test/java/org/ipro/form/registry/FormRegistrySelectionTest.java',
            'src/test/java/org/ip/views/admin/ExplorerTreeModelTest.java',
            'src/test/java/org/ip/views/admin/EntityExplorerPilotBoundaryTest.java',
            'src/test/java/org/ip/views/EntityExplorerAddressWiringTest.java',
            'src/test/java/org/ip/views/EntityExplorerRouteGateTest.java',
            'src/test/java/org/ipro/PlatformVaadinSurfaceTest.java')
        [IO.File]::WriteAllLines("$pilotOutput/test-compile.args",[string[]]$arguments,[Text.UTF8Encoding]::new($false))
        & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$pilotOutput/test-compile.args") *> "$pilotOutput/test-compile.log"
        if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed; see test-compile.log.' }
        $agent = @(Get-ChildItem outputs/entity-explorer-p1/deps -Recurse -Filter 'mockito-core-*.jar')
        if ($agent.Count -ne 1) { throw 'Expected one Mockito agent.' }
        & (Join-Path $JavaHome 'bin/java.exe') ('-javaagent:'+$agent[0].FullName) '-Dfile.encoding=UTF-8' `
            ("-Djava.io.tmpdir=$pilotOutput/tmp") -cp ('outputs/entity-explorer-p2/test-classes;'+$classpath) ExplorerStructureChecks `
            org.ip.views.admin.EntityExplorerPilotPanelTest org.ip.views.admin.EntityExplorerPilotBoundaryTest `
            org.ip.views.admin.ExplorerSourceReaderTest org.ipro.form.builder.ItemFormClassDescriptionTest `
            org.ip.vaadin.explorer.EntitySummaryAssemblerTest org.ip.form.registry.FormRegistryDuplicateTest `
            org.ip.form.registry.FormRegistryLifecycleTest org.ipro.form.registry.FormRegistrySelectionTest `
            org.ip.views.admin.ExplorerTreeModelTest `
            org.ip.views.EntityExplorerAddressWiringTest org.ip.views.EntityExplorerRouteGateTest `
            org.ipro.PlatformVaadinSurfaceTest *> "$pilotOutput/test.log"
        Get-Content "$pilotOutput/test.log" -Tail 35
        if ($LASTEXITCODE -ne 0) { throw 'P2 checks failed; see test.log.' }
        return
    }
    $propertiesPath = (Resolve-Path -LiteralPath $PilotProperties).Path
    $urls = @(Get-Content -LiteralPath $propertiesPath | Where-Object { $_ -match '^spring.datasource.url=' })
    if ($urls.Count -ne 1 -or $urls[0] -notmatch '^spring.datasource.url=(jdbc:postgresql://(?:localhost|127\.0\.0\.1):\d+/(?:navigation_pilot|entity_explorer_pilot)_[a-f0-9]{32})$') {
        throw 'Only the prepared isolated local pilot database is allowed.'
    }
    $url = $Matches[1]
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,$Port)
    try { $listener.Start() } finally { $listener.Stop() }
    $main = 'org.ip.Application'
    if ($DensityFixture) {
        New-Item -ItemType Directory -Force -Path "$pilotOutput/density-classes" | Out-Null
        $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
            '-d','outputs/entity-explorer-p2/density-classes',
            'src/test/java/org/ip/views/admin/fixture/EntityExplorerDensityFixture.java',
            'scripts/entity-explorer-pilot/java/org/explorerpilot/DensityLauncher.java')
        [IO.File]::WriteAllLines("$pilotOutput/density-compile.args",[string[]]$arguments,[Text.UTF8Encoding]::new($false))
        & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$pilotOutput/density-compile.args") *> "$pilotOutput/density-compile.log"
        if ($LASTEXITCODE -ne 0) { throw 'Density fixture compilation failed; see density-compile.log.' }
        $classpath = 'outputs/entity-explorer-p2/density-classes;' + $classpath
        $main = 'org.explorerpilot.DensityLauncher'
    }
    New-Item -ItemType Directory -Force -Path "$pilotOutput/home-$Port" | Out-Null
    $arguments = @('-Dfile.encoding=UTF-8',('"-Duser.home='+$pilotOutput.Replace('\','/')+"/home-$Port"+'"'),
        '-cp',('"'+$classpath.Replace('\','/')+'"'),$main,
        ('"--spring.config.location=classpath:application.properties,file:'+ $propertiesPath.Replace('\','/')+'[.properties]"'),
        "--spring.datasource.url=$url", "--server.port=$Port", '--server.address=127.0.0.1',
        "--server.servlet.session.cookie.name=ENTITY_EXPLORER_PILOT_$Port",
        '--spring.jpa.hibernate.ddl-auto=none','--spring.sql.init.mode=never',
        '--app.demo-data.enabled=false','--app.security-baseline.enabled=false','--app.accounting-pilot.seed=false',
        '--platform.navigation.enabled=false','--platform.rest.web.enabled=false',
        ('--app.entity-explorer.ux-pilot.enabled='+(!$Legacy).ToString().ToLowerInvariant()),
        '--vaadin.launch-browser=false','--vaadin.allowed-packages=org.ip,org.ipro,com.vaadin,org.vaadin',
        '"--vaadin.nodeFolder=C:/Program Files/nodejs"')
    $argumentFile = Join-Path $pilotOutput "server-$Port.args"
    if ($Sources) {
        $arguments += @('--spring.profiles.active=dev', '--app.entity-explorer.sources.enabled=true',
            ('"--app.entity-explorer.sources.project-root=' + $pilotRoot.Replace('\','/') + '"'))
    }
    if ($HotDeploy) {
        $arguments += @('--vaadin.frontend.hotdeploy=true',
            "--vaadin.build.folder=outputs/entity-explorer-p2/frontend-build-$Port")
    }
    [IO.File]::WriteAllLines($argumentFile,[string[]]$arguments,[Text.UTF8Encoding]::new($false))
    $process = Start-Process -FilePath (Join-Path $JavaHome 'bin/java.exe') -ArgumentList ('"@'+$argumentFile.Replace('\','/')+'"') `
        -WorkingDirectory $pilotRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput "$pilotOutput/server-$Port.log" -RedirectStandardError "$pilotOutput/server-$Port-error.log"
    @{processId=$process.Id; executable=(Join-Path $JavaHome 'bin/java.exe'); startTimeUtcTicks=$process.StartTime.ToUniversalTime().Ticks} |
        ConvertTo-Json | Set-Content -LiteralPath $stateFile -Encoding utf8
    Write-Output "Starting compact pilot: http://127.0.0.1:$Port/entity-explorer/nomenclature (PID $($process.Id), fixture=$DensityFixture). See server-$Port.log."
} finally { Pop-Location }
