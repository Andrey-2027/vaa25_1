[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$NodeFolder = 'C:/Program Files/nodejs',
    [switch]$QuerySource,
    [switch]$PrepareOnly
)

# Local UI fixture: fixed identity, loopback binding, no working database.
# -QuerySource uses disposable H2 data and the actual UDR query pipeline.
# Run run-unidr-u4.ps1 first to compile the screen and fixture.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$previousPath = $env:PATH
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $output = Join-Path (Get-Location) 'outputs/unidr-u4'
    $compileArguments = Join-Path $output 'compile.args'
    $fixtureClass = if ($QuerySource) { 'UniDrQuerySmokeApplication' } else { 'UniDrUiSmokeApplication' }
    if (!(Test-Path -LiteralPath $compileArguments) -or !(Test-Path "target/test-classes/org/ip/views/unidr/smoke/$fixtureClass.class")) {
        throw 'Run scripts/run-unidr-u4.ps1 first.'
    }
    if (!(Test-Path (Join-Path $NodeFolder 'node.exe')) -or !(Test-Path 'node_modules/.bin/tsc.cmd')) {
        throw 'Existing Node.js and installed frontend dependencies are required.'
    }
    $compileClasspath = (Get-Content -LiteralPath $compileArguments)[8].Trim('"')
    $agent = $compileClasspath -split ';' | Where-Object { (Split-Path -Leaf $_) -match '^mockito-core-[\d.]+\.jar$' } | Select-Object -First 1
    if (!$agent) { throw 'Mockito startup agent is missing from the fixture classpath.' }
    $fixtureHome = Join-Path $output 'home'
    New-Item -ItemType Directory -Force -Path $fixtureHome | Out-Null
    $arguments = @('-Dfile.encoding=UTF-8', ('"-Duser.home='+$fixtureHome.Replace('\','/')+'"'),
        ('"-javaagent:'+$agent+'"'), '-cp', ('"target/test-classes;target/classes;'+$compileClasspath+'"'),
        $(if($QuerySource){'org.ip.views.unidr.smoke.UniDrQuerySmokeApplication'}else{'org.ip.views.unidr.smoke.UniDrUiSmokeApplication'}), ('"--vaadin.nodeFolder='+$NodeFolder.Replace('\','/')+'"'), '--vaadin.frontend.hotdeploy=true')
    $argumentFile = Join-Path $output 'smoke.args'
    [IO.File]::WriteAllLines($argumentFile,[string[]]$arguments,[Text.UTF8Encoding]::new($false))
    if ($PrepareOnly) { return }
    $env:PATH = (Join-Path (Get-Location) 'node_modules/.bin') + ';' + $NodeFolder + ';' + $previousPath
    Write-Host 'UniDR fixture: http://127.0.0.1:8086/unidr (Ctrl+C to stop)'
    & (Join-Path $JavaHome 'bin/java.exe') ('@'+$argumentFile)
    if ($LASTEXITCODE -ne 0) { throw "Fixture exited with code $LASTEXITCODE" }
} finally {
    $env:PATH = $previousPath
    Pop-Location
}
