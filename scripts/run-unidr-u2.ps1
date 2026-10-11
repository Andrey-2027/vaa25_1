[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MavenCommand,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [switch]$Postgres
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
    $output = Join-Path (Get-Location) 'outputs\unidr-u2'
    New-Item -ItemType Directory -Force -Path $output,'target/classes','target/test-classes' | Out-Null
    & $MavenCommand -o resources:resources resources:testResources dependency:build-classpath '-Dmdep.includeScope=test' `
        '-Dmdep.outputFile=outputs/unidr-u2/classpath.txt' *> (Join-Path $output 'dependencies.log')
    if ($LASTEXITCODE -ne 0) { throw "Classpath/resources preparation failed; see $output\dependencies.log" }
    $classpath = (Get-Content -LiteralPath (Join-Path $output 'classpath.txt') -Raw).Trim()
    # All referenced application/report classes are compiled from source by javac.
    # Model entities are explicit inputs because Hibernate discovers them at runtime.
    $arguments = @('--release','21','-parameters','-encoding','UTF-8','-sourcepath','src/main/java',
        '-cp',('"'+$classpath.Replace('\','/')+'"'),'-d','target/classes')
    $arguments += Get-ChildItem -LiteralPath 'src/main/java/org/ip/unidr' -Recurse -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $arguments += Get-ChildItem -LiteralPath 'src/main/java/org/ip/model' -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $arguments += 'src/main/java/org/ip/Application.java'
    $arguments += 'src/main/java/org/ipro/reportstudio/render/JasperReportCompiler.java'
    $arguments += 'src/main/java/org/ipro/jr/run/JpqlQueryExecuterBundle.java'
    $argumentFile = Join-Path $output 'compile.args'
    [IO.File]::WriteAllLines($argumentFile,[string[]]$arguments,[Text.UTF8Encoding]::new($false))
    & $javac ('@'+$argumentFile) *> (Join-Path $output 'compile-scoped.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $output 'compile-scoped.log') -Tail 40; throw 'U2 source compilation failed.' }
    $testClasspath = 'target/classes;' + $classpath
    $testArguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$testClasspath.Replace('\','/')+'"'),'-d','target/test-classes')
    foreach ($directory in @('model','query','run','config')) {
        $testArguments += Get-ChildItem -LiteralPath "src/test/java/org/ip/unidr/$directory" -Filter '*.java' |
            ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    }
    $testArguments += Get-ChildItem -LiteralPath 'src/test/java/org/ipro/reportstudio/query' -Filter 'ReportQueryAssemblyServiceVisual*Test.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $testArgumentFile = Join-Path $output 'compile-tests.args'
    [IO.File]::WriteAllLines($testArgumentFile,[string[]]$testArguments,[Text.UTF8Encoding]::new($false))
    & $javac ('@'+$testArgumentFile) *> (Join-Path $output 'compile-tests.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $output 'compile-tests.log') -Tail 40; throw 'U2 test compilation failed.' }
    $mockito = @($classpath -split ';' | Where-Object { $_ -match '[\\/]mockito-core[\\/].*\.jar$' })
    if ($mockito.Count -ne 1) { throw 'Expected one Mockito agent in the test classpath.' }
    $runArguments = @('-o',('-DargLine=-javaagent:"'+$mockito[0].Replace('\','/')+'" -Dfile.encoding=UTF-8'))
    if ($Postgres) {
        $runArguments += @('-Dunidr.u2.postgres=true','-Dtest=UniDrReceivingItemsQueryTest')
        $logName = 'postgresql-test.log'
    } else {
        $runArguments += '-Dtest=UniDrDefinitionTest,UniDrDefinitionJsonCodecTest,UniDrRunParameterResolverTest,UniDrRunServiceTest,UniDrQueryAdapterTest,UniDrQueryServiceTest,UniDrConfigurationTest,UniDrReceivingItemsQueryTest,ReportQueryAssemblyServiceVisual*Test'
        $logName = 'test.log'
    }
    & $MavenCommand @runArguments surefire:test *> (Join-Path $output $logName)
    $testExit = $LASTEXITCODE
    Get-Content (Join-Path $output $logName) -Tail 30
    if ($testExit -ne 0) { throw "U2 tests failed; see $output\$logName" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
