[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository',
    [switch]$SkipCompile,
    [string[]]$TestClasses = @()
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$pilotRoot = Split-Path $PSScriptRoot -Parent
$pilotOutput = Join-Path $pilotRoot 'outputs/entity-explorer-p1'
$previousJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $pilotRoot
try {
    $env:JAVA_HOME = $JavaHome
    New-Item -ItemType Directory -Force -Path "$pilotOutput/classes","$pilotOutput/test-classes","$pilotOutput/tmp" | Out-Null
    if (!$SkipCompile) {
        & $Maven -o "-Dmaven.repo.local=$LocalRepository" dependency:build-classpath '-Dmdep.includeScope=test' `
            '-Dmdep.outputFile=outputs/entity-explorer-p1/classpath.txt' *> "$pilotOutput/dependencies.log"
        if ($LASTEXITCODE -ne 0) { throw 'Classpath resolution failed.' }
        $copies = @(); $index = 0
        foreach ($jar in ((Get-Content "$pilotOutput/classpath.txt" -Raw).Trim() -split ';')) {
            $directory = Join-Path "$pilotOutput/deps" ($index++).ToString('D4')
            New-Item -ItemType Directory -Force -Path $directory | Out-Null
            $copy = Join-Path $directory (Split-Path $jar -Leaf)
            Copy-Item -LiteralPath $jar -Destination $copy -Force
            $copies += $copy
        }
        $classpath = 'platform-rls/target/classes;' + ($copies -join ';')
        [IO.File]::WriteAllText("$pilotOutput/local-classpath.txt", $classpath)
        $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
            '-d','outputs/entity-explorer-p1/classes','platform-core/src/main/java/org/ipro/metadata/FieldMetadataInfo.java',
            'platform-vaadin/src/main/java/org/ipro/form/host/FormRouteUrlBridge.java')
        $arguments += Get-ChildItem platform-vaadin/src/main/java/org/ipro/vaadin/explorer -Recurse -Filter '*.java' |
            ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
        $arguments += Get-ChildItem src/main/java -Recurse -Filter '*.java' |
            ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
        [IO.File]::WriteAllLines("$pilotOutput/compile.args",[string[]]$arguments,[Text.UTF8Encoding]::new($false))
        & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$pilotOutput/compile.args") *> "$pilotOutput/compile.log"
        if ($LASTEXITCODE -ne 0) { throw 'Source compilation failed; see compile.log.' }
    }
    $classpath = 'outputs/entity-explorer-p1/classes;src/main/resources;' + (Get-Content "$pilotOutput/local-classpath.txt" -Raw).Trim()
    # Read only the resolved classpath: old runs may have left jars at other numbered paths.
    $dependencyFiles = (Get-Content "$pilotOutput/local-classpath.txt" -Raw).Trim() -split ';'
    $engine = @($dependencyFiles | Where-Object { (Split-Path $_ -Leaf) -like 'junit-platform-engine-*.jar' } | Get-Item)
    if ($engine.Count -ne 1) { throw 'Expected one JUnit engine.' }
    $version = $engine[0].BaseName.Substring('junit-platform-engine-'.Length)
    Copy-Item -LiteralPath (Join-Path $LocalRepository "org/junit/platform/junit-platform-launcher/$version/junit-platform-launcher-$version.jar") `
        -Destination "$pilotOutput/junit-launcher.jar" -Force
    $classpath += ';outputs/entity-explorer-p1/junit-launcher.jar'
    $arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
        '-sourcepath','src/test/java;platform-vaadin/src/test/java', '-d','outputs/entity-explorer-p1/test-classes',
        'scripts/entity-explorer-pilot/ExplorerStructureChecks.java',
        'scripts/entity-explorer-pilot/ExplorerSnapshotMeasurement.java',
        'src/test/java/org/ip/views/admin/EntityExplorerPilotBoundaryTest.java',
        'src/test/java/org/ip/views/admin/EntityExplorerCatalogTest.java',
        'src/test/java/org/ip/views/admin/EntityExplorerWorkspaceTest.java',
        'src/test/java/org/ip/views/admin/ExplorerTreeModelTest.java',
        'src/test/java/org/ip/views/admin/EntityStructureNavigationTest.java',
        'src/test/java/org/ip/views/admin/SubsystemStructureNavigationTest.java',
        'src/test/java/org/ip/views/admin/EntityDraftCatalogAdapterTest.java',
        'src/test/java/org/ip/views/admin/DraftEditorSessionTest.java',
        'src/test/java/org/ip/views/admin/EntityDraftEditorViewTest.java',
        'src/test/java/org/ip/views/admin/EntityModificationTest.java',
        'src/test/java/org/ip/views/admin/EntityDraftWorkspaceTest.java',
        'src/test/java/org/ip/views/workspace/EntityDraftWorkspaceContractTest.java',
        'src/test/java/org/ip/views/workspace/WorkspaceDirtyGuardTest.java',
        'src/test/java/org/ip/views/workspace/WorkspaceRouteStateTest.java',
        'src/test/java/org/ip/views/EntityExplorerAddressWiringTest.java',
        'src/test/java/org/ip/views/EntityExplorerRouteGateTest.java',
        'platform-vaadin/src/test/java/org/ipro/form/host/FormRouteUrlBridgeTest.java',
        'platform-core/src/test/java/org/ipro/metadata/FieldMetadataInlineFilterTest.java',
        'platform-core/src/test/java/org/ipro/metadata/FieldMetadataRequiredOriginTest.java',
        'src/test/java/org/ipro/PlatformApiBaselineTest.java',
        'src/test/java/org/ipro/PlatformCoreSurfaceTest.java',
        'src/test/java/org/ipro/PlatformVaadinSurfaceTest.java')
    $arguments += Get-ChildItem platform-vaadin/src/test/java/org/ipro/vaadin/explorer -Recurse -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $arguments += Get-ChildItem src/test/java/org/ip/vaadin/explorer -Filter '*Test.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    [IO.File]::WriteAllLines("$pilotOutput/test-compile.args",[string[]]$arguments,[Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$pilotOutput/test-compile.args") *> "$pilotOutput/test-compile.log"
    if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed; see test-compile.log.' }
    $agent = @($dependencyFiles | Where-Object { (Split-Path $_ -Leaf) -like 'mockito-core-*.jar' } | Get-Item)
    if ($agent.Count -ne 1) { throw 'Expected one Mockito agent.' }
    $runtime = 'outputs/entity-explorer-p1/test-classes;src/test/resources;platform-vaadin/src/test/resources;' + $classpath
    [IO.File]::WriteAllText("$pilotOutput/runtime-classpath.txt",$runtime)
    function Invoke-ExplorerChecks([string]$logName, [string[]]$options, [string[]]$classes) {
        $launchArgs = $options + @('-Dfile.encoding=UTF-8', "-Djava.io.tmpdir=$pilotOutput/tmp",
            '-cp', $runtime, 'ExplorerStructureChecks') + $classes
        $launchArgs = $launchArgs | ForEach-Object { '"' + $_.Replace('\','/').Replace('"','\"') + '"' }
        $argFile = "$pilotOutput/$logName.args"
        [IO.File]::WriteAllLines($argFile, [string[]]$launchArgs, [Text.UTF8Encoding]::new($false))
        & (Join-Path $JavaHome 'bin/java.exe') ('@' + $argFile) *> "$pilotOutput/$logName.log"
        return $LASTEXITCODE
    }
    $testExit = Invoke-ExplorerChecks 'test' @('-javaagent:' + $agent[0].FullName) $TestClasses
    Get-Content "$pilotOutput/test.log" -Tail 40
    if ($testExit -ne 0) { throw 'P1 checks failed; see test.log.' }
    foreach ($artifact in @('platform-core','platform-vaadin')) {
        if ((Invoke-ExplorerChecks "$artifact-api-check" @("-Dplatform.api.baseline.artifact=$artifact") @('org.ipro.PlatformApiBaselineTest')) -ne 0) {
            throw "API baseline failed: $artifact; see its api-check.log."
        }
    }
    if ((Invoke-ExplorerChecks 'vaadin-surface-check' @() @('org.ipro.PlatformVaadinSurfaceTest')) -ne 0) {
        throw 'Vaadin surface check failed; see vaadin-surface-check.log.'
    }
    Write-Output 'P1 functional, closed-dictionary, API and Vaadin surface checks passed.'
} finally { Pop-Location; $env:JAVA_HOME = $previousJavaHome }
