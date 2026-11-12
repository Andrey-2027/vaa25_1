[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$registryRoot = Split-Path $PSScriptRoot -Parent
$registryOutput = Join-Path $registryRoot 'outputs/entity-registry-r2'
$registryPreviousJava = $env:JAVA_HOME
Push-Location -LiteralPath $registryRoot
try {
    $env:JAVA_HOME = $JavaHome
    New-Item -ItemType Directory -Force -Path "$registryOutput/classes","$registryOutput/tests","$registryOutput/tmp" | Out-Null
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" dependency:build-classpath '-Dmdep.includeScope=test' `
        "-Dmdep.outputFile=$registryOutput/classpath.txt" *> "$registryOutput/dependencies.log"
    if ($LASTEXITCODE) { throw 'Dependency resolution failed' }
    $registryDependencies = (Get-Content "$registryOutput/classpath.txt" -Raw).Trim()
    $registryArguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$registryDependencies.Replace('\','/')+'"'),'-d','outputs/entity-registry-r2/classes')
    $registryArguments += Get-ChildItem platform-vaadin/src/main/java/org/ipro/vaadin/explorer -Recurse -Filter '*.java' | ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'}
    [IO.File]::WriteAllLines("$registryOutput/compile.args",[string[]]$registryArguments)
    & "$JavaHome/bin/javac.exe" ('@'+"$registryOutput/compile.args") *> "$registryOutput/compile.log"
    if ($LASTEXITCODE) { throw 'Platform source compilation failed; see compile.log' }
    $registryJars = $registryDependencies -split ';'
    $registryEngine = @( $registryJars | Where-Object {(Split-Path $_ -Leaf) -like 'junit-platform-engine-*.jar'} | Get-Item )
    if ($registryEngine.Count -ne 1) { throw 'Expected one JUnit engine' }
    $registryVersion = $registryEngine[0].BaseName.Substring('junit-platform-engine-'.Length)
    $registryLauncher = Join-Path $LocalRepository "org/junit/platform/junit-platform-launcher/$registryVersion/junit-platform-launcher-$registryVersion.jar"
    $registryClasspath = "outputs/entity-registry-r2/classes;platform-vaadin/src/main/resources;$registryLauncher;$registryDependencies"
    $registryArguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$registryClasspath.Replace('\','/')+'"'),'-d','outputs/entity-registry-r2/tests',
        'scripts/entity-explorer-pilot/ExplorerStructureChecks.java','platform-vaadin/src/test/java/org/ipro/vaadin/explorer/RegistryFixture.java',
        'scripts/entity-registry-r2/RegistryPlatformBoundaryChecks.java',
        'platform-vaadin/src/test/java/org/ipro/vaadin/explorer/ui/EntityExplorerRegistryTest.java')
    [IO.File]::WriteAllLines("$registryOutput/test-compile.args",[string[]]$registryArguments)
    & "$JavaHome/bin/javac.exe" ('@'+"$registryOutput/test-compile.args") *> "$registryOutput/test-compile.log"
    if ($LASTEXITCODE) { throw 'Platform test compilation failed; see test-compile.log' }
    $registryRuntime = "outputs/entity-registry-r2/tests;$registryClasspath"
    $registryProbe = @('-cp',('"'+$registryRuntime.Replace('\','/')+'"'),'org.ipro.registryfixture.RegistryPlatformBoundaryChecks')
    [IO.File]::WriteAllLines("$registryOutput/boundary-run.args",[string[]]$registryProbe)
    & "$JavaHome/bin/java.exe" ('@'+"$registryOutput/boundary-run.args") *> "$registryOutput/boundary.log"
    if ($LASTEXITCODE) { throw 'Platform boundary probe failed; see boundary.log' }
    $registryArguments = @('-Dfile.encoding=UTF-8',('-Djava.io.tmpdir="'+"$registryOutput/tmp".Replace('\','/')+'"'),'-cp',('"'+$registryRuntime.Replace('\','/')+'"'),
        'ExplorerStructureChecks','org.ipro.vaadin.explorer.ui.EntityExplorerRegistryTest')
    [IO.File]::WriteAllLines("$registryOutput/test-run.args",[string[]]$registryArguments)
    & "$JavaHome/bin/java.exe" ('@'+"$registryOutput/test-run.args") *> "$registryOutput/test.log"
    Get-Content "$registryOutput/test.log" -Tail 50
    if ($LASTEXITCODE) { throw 'Platform UI checks failed' }
    Write-Output 'R2 platform-only checks passed; no application classes on the runtime classpath.'
} finally { Pop-Location; $env:JAVA_HOME = $registryPreviousJava }
