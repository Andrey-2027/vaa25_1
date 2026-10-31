[CmdletBinding()]
param([string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$hostRoot = Split-Path $PSScriptRoot -Parent
$hostOutput = Join-Path $hostRoot 'outputs/entity-explorer-p8-0'
Push-Location -LiteralPath $hostRoot
try {
    New-Item -ItemType Directory -Force -Path "$hostOutput/classes","$hostOutput/test-classes" | Out-Null
    if (!(Test-Path outputs/entity-explorer-p1/local-classpath.txt)) { throw 'Prepare the existing pilot test dependencies first.' }
    $deps = (Get-Content outputs/entity-explorer-p1/local-classpath.txt -Raw).Trim()
    $classpath = 'outputs/entity-explorer-p2/classes;outputs/entity-explorer-p1/junit-launcher.jar;' + $deps
    $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
        '-d','outputs/entity-explorer-p8-0/classes',
        'src/main/java/org/ip/views/workspace/Entry.java',
        'src/main/java/org/ip/views/workspace/Workspace.java',
        'src/main/java/org/ip/views/workspace/WorkspaceManager.java',
        'platform-vaadin/src/main/java/org/ipro/form/host/FormRouteUrlBridge.java')
    [IO.File]::WriteAllLines("$hostOutput/host-compile.args",[string[]]$arguments)
    & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$hostOutput/host-compile.args") *> "$hostOutput/host-compile.log"
    if ($LASTEXITCODE -ne 0) { throw 'Host source compilation failed; see host-compile.log.' }
    $classpath = 'outputs/entity-explorer-p8-0/classes;' + $classpath
    $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
        '-d','outputs/entity-explorer-p8-0/test-classes',
        'scripts/entity-explorer-pilot/ExplorerStructureChecks.java',
        'src/test/java/org/ip/views/workspace/EntityDraftWorkspaceContractTest.java',
        'src/test/java/org/ip/views/workspace/WorkspaceDirtyGuardTest.java',
        'src/test/java/org/ip/views/workspace/WorkspaceRouteStateTest.java',
        'platform-vaadin/src/test/java/org/ipro/form/host/FormRouteUrlBridgeTest.java',
        'src/test/java/org/ipro/WorkspaceManifestTest.java')
    [IO.File]::WriteAllLines("$hostOutput/host-test-compile.args",[string[]]$arguments)
    & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$hostOutput/host-test-compile.args") *> "$hostOutput/host-test-compile.log"
    if ($LASTEXITCODE -ne 0) { throw 'Host test compilation failed; see host-test-compile.log.' }
    $classpath = 'outputs/entity-explorer-p8-0/test-classes;src/test/resources;' + $classpath
    $agent = @(Get-ChildItem outputs/entity-explorer-p1/deps -Recurse -Filter 'mockito-core-*.jar')
    if ($agent.Count -ne 1) { throw 'Expected one Mockito agent.' }
    & (Join-Path $JavaHome 'bin/java.exe') ('-javaagent:'+$agent[0].FullName) '-Dfile.encoding=UTF-8' -cp $classpath `
        ExplorerStructureChecks org.ip.views.workspace.EntityDraftWorkspaceContractTest `
        org.ip.views.workspace.WorkspaceDirtyGuardTest org.ip.views.workspace.WorkspaceRouteStateTest `
        org.ipro.form.host.FormRouteUrlBridgeTest *> "$hostOutput/host-tests.log"
    if ($LASTEXITCODE -ne 0) { throw 'Host checks failed; see host-tests.log.' }
    Write-Output 'P8.0 host contract checks passed.'
} finally { Pop-Location }
