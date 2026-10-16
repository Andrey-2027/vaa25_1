[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MavenCommand,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$LocalRepository = (Join-Path $env:USERPROFILE '.m2/repository'),
    [string]$TestPattern
)

# Scoped gate: compiles actual UniDR sources and their source dependencies.
# Does not replace the full application build or modify other source files.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $version = & $javac -version 2>&1
    if ("$version" -notmatch '^javac 21\.') { throw "JDK 21 required; found $version" }
    $output = Join-Path (Get-Location) 'outputs\unidr-u4'
    New-Item -ItemType Directory -Force -Path $output,'target/classes','target/test-classes' | Out-Null
    & $MavenCommand -o "-Dmaven.repo.local=$LocalRepository" resources:resources resources:testResources dependency:build-classpath '-Dmdep.includeScope=test' `
        '-Dmdep.outputFile=outputs/unidr-u4/classpath.txt' *> (Join-Path $output 'dependencies.log')
    if ($LASTEXITCODE -ne 0) { throw "Classpath/resources preparation failed; see $output\dependencies.log" }
    $classpath = (Get-Content -LiteralPath (Join-Path $output 'classpath.txt') -Raw).Trim()
    # javac ZIP close can fail to resolve sandboxed user-cache paths on Windows.
    # Use byte-identical copies under the workspace; dependency coordinates stay unchanged.
    $compileJars = @()
    $jarIndex = 0
    foreach ($jar in ($classpath -split ';')) {
        $copyDirectory = Join-Path $output ('deps/' + ($jarIndex++).ToString('D4'))
        New-Item -ItemType Directory -Force -Path $copyDirectory | Out-Null
        $copyPath = Join-Path $copyDirectory (Split-Path -Leaf $jar)
        Copy-Item -LiteralPath $jar -Destination $copyPath -Force
        $compileJars += $copyPath
    }
    $compileClasspath = $compileJars -join ';'
    # All referenced application/report classes are compiled from source by javac.
    # Model entities are explicit inputs because Hibernate discovers them at runtime.
    $arguments = @('--release','21','-parameters','-encoding','UTF-8','-sourcepath','src/main/java;platform-register/src/main/java',
        '-cp',('"'+$compileClasspath.Replace('\','/')+'"'),'-d','target/classes')
    $arguments += Get-ChildItem -LiteralPath 'src/main/java/org/ip/unidr' -Recurse -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $arguments += Get-ChildItem -LiteralPath 'src/main/java/org/ip/model' -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $arguments += 'src/main/java/org/ip/Application.java'
    $arguments += 'src/main/java/org/ip/config/AuditConfig.java'
    # Register classes have moved to their module; compile the current sources rather
    # than relying on an older installed snapshot during the scoped application run.
    $arguments += Get-ChildItem -LiteralPath 'platform-register/src/main/java' -Recurse -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $arguments += Get-ChildItem -LiteralPath 'src/main/java/org/ip/views/unidr' -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $arguments += 'src/main/java/org/ipro/reportstudio/render/JasperReportCompiler.java'
    $arguments += 'src/main/java/org/ipro/jr/run/JpqlQueryExecuterBundle.java'
    $arguments += 'src/main/java/org/ipro/ureport/catalog/ReportCatalogService.java'
    $arguments += 'src/main/java/org/ipro/ureport/config/UreportAutoConfiguration.java'
    $arguments += 'src/main/java/org/ip/views/reportstudio/ReportCatalogView.java'
    $arguments += 'src/main/java/org/ip/views/reportstudio/ReportPrintDialog.java'
    $argumentFile = Join-Path $output 'compile.args'
    [IO.File]::WriteAllLines($argumentFile,[string[]]$arguments,[Text.UTF8Encoding]::new($false))
    & $javac ('@'+$argumentFile) *> (Join-Path $output 'compile-scoped.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $output 'compile-scoped.log') -Tail 40; throw 'U4 source compilation failed.' }
    $testClasspath = 'target/classes;' + $compileClasspath
    $testArguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$testClasspath.Replace('\','/')+'"'),'-d','target/test-classes')
    foreach ($directory in @('model','query','run','config','render','store')) {
        $testArguments += Get-ChildItem -LiteralPath "src/test/java/org/ip/unidr/$directory" -Filter '*.java' |
            ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    }
    $testArguments += Get-ChildItem -LiteralPath 'src/test/java/org/ipro/reportstudio/query' -Filter 'ReportQueryAssemblyServiceVisual*Test.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $testArguments += Get-ChildItem -LiteralPath 'src/test/java/org/ip/views/unidr' -Recurse -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $testArguments += 'src/test/java/org/ip/views/reportstudio/ReportQueryDialogTest.java'
    $testArguments += 'src/test/java/org/ipro/reportstudio/query/editor/QueryEditorAnalysisServiceTest.java'
    $testArguments += 'src/test/java/org/ipro/reportstudio/query/editor/ReportQueryEditorTest.java'
    $testArguments += 'src/test/java/org/ipro/ReportFormSeamTest.java'
    $testArguments += 'src/test/java/org/ip/views/components/ReportParamFormTest.java'
    $testArguments += Get-ChildItem -LiteralPath 'src/test/java/org/ipro/ureport' -Filter 'ReportCatalog*Test.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $testArguments += 'src/test/java/org/ipro/ureport/ReportEngineCapabilitiesTest.java'
    $testArguments += 'src/test/java/org/ip/views/reportstudio/ReportCatalogViewTest.java'
    $testArgumentFile = Join-Path $output 'compile-tests.args'
    [IO.File]::WriteAllLines($testArgumentFile,[string[]]$testArguments,[Text.UTF8Encoding]::new($false))
    & $javac ('@'+$testArgumentFile) *> (Join-Path $output 'compile-tests.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $output 'compile-tests.log') -Tail 40; throw 'U4 test compilation failed.' }
    $mockito = @($classpath -split ';' | Where-Object { $_ -match '[\\/]mockito-core[\\/].*\.jar$' })
    if ($mockito.Count -ne 1) { throw 'Expected one Mockito agent in the test classpath.' }
    # Keep JUnit's real-path checks inside the writable workspace on Windows.
    $testTemp=Join-Path $output 'tmp'
    New-Item -ItemType Directory -Force -Path $testTemp | Out-Null
    $runArguments = @('-o',"-Dmaven.repo.local=$LocalRepository",('-DargLine=-javaagent:"'+$mockito[0].Replace('\','/')+'" -Dfile.encoding=UTF-8 -Djava.io.tmpdir="'+$testTemp.Replace('\','/')+'"'))
    $runArguments += '-Dtest=UniDrDefinitionTest,UniDrDefinitionV2Test,UniDrDefinitionJsonCodecTest,UniDrAppearanceTest,UniDrRunParameterResolverTest,UniDrRunServiceTest,UniDrQueryAdapterTest,UniDrQueryServiceTest,UniDrQuerySourceTest,UniDrTableSourcesTest,UniDrConfigurationTest,UniDrReceivingItemsQueryTest,UniDrReportCompilerTest,UniDrRenderedReportTest,ReportQueryAssemblyServiceVisual*Test,ReportQueryDialogTest,QueryEditorAnalysisServiceTest,ReportQueryEditorTest,ReportFormSeamTest,UniDrEditorTest,UniDrRunControllerTest,UniDrQueryUiTest,UniDrDownloadsTest,UniDrFileStoreTest,UniDrFileUiTest,ReportCatalog*Test,ReportEngineCapabilitiesTest'
    $logName = 'test.log'
    if (-not $TestPattern) { $runArguments = @($runArguments | ForEach-Object { if ($_ -like '-Dtest=*') { $_ + ',UniDrFilterSupportTest,UniDrCanonicalValuesTest,UniDrNativeFilterEditorTest,UniDrSourceReconcilerTest,UniDrSourceChangeUiTest,ReportParamFormTest' } else { $_ } }) }
    if ($TestPattern) { $runArguments = @($runArguments | Where-Object { $_ -notlike '-Dtest=*' }); $runArguments += "-Dtest=$TestPattern" }
    & $MavenCommand @runArguments surefire:test *> (Join-Path $output $logName)
    $testExit = $LASTEXITCODE
    Get-Content (Join-Path $output $logName) -Tail 30
    if ($testExit -ne 0) { throw "U4 tests failed; see $output\$logName" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
