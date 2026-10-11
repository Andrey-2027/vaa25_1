[CmdletBinding()]
param(
    [string]$MavenCommand,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Postgres
)

# U0 only: compiles production plus these experiment sources, then invokes Surefire directly.
# This is intentionally separate from the full repository test gate, which must still be run.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
if (-not $MavenCommand) {
    $mavenOnPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($mavenOnPath) { $MavenCommand = $mavenOnPath.Source }
    elseif ($env:IDEA_MAVEN_HOME) { $MavenCommand = Join-Path $env:IDEA_MAVEN_HOME 'bin\mvn.cmd' }
    else { throw 'Pass -MavenCommand with the path to mvn.cmd.' }
}
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\javac.exe'))) {
    throw 'Pass -JavaHome with a JDK 21 directory.'
}
$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
Push-Location $projectDirectory
try {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $version = & $javac -version 2>&1
    if ("$version" -notmatch '^javac 21\.') { throw "JDK 21 required; found $version" }
    $output = Join-Path $projectDirectory 'outputs\unidr-u0'
    New-Item -ItemType Directory -Force -Path $output | Out-Null

    & $MavenCommand -o compile resources:testResources dependency:build-classpath `
        '-Dmdep.includeScope=test' '-Dmdep.outputFile=outputs/unidr-u0/classpath.txt' `
        *> (Join-Path $output 'prepare.log')
    if ($LASTEXITCODE -ne 0) { throw "Preparation failed; see $output\prepare.log" }
    $dependencyClasspath = (Get-Content -LiteralPath (Join-Path $output 'classpath.txt') -Raw).Trim()
    $classpath = 'target/classes;target/test-classes;' + $dependencyClasspath
    $arguments = @('-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),'-d','target/test-classes')
    $arguments += Get-ChildItem -LiteralPath 'src/test/java/org/ip/unidr/spike' -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $argumentFile = Join-Path $output 'compile.args'
    [System.IO.File]::WriteAllLines($argumentFile, [string[]]$arguments, [System.Text.UTF8Encoding]::new($false))
    & $javac ('@'+$argumentFile) *> (Join-Path $output 'compile.log')
    if ($LASTEXITCODE -ne 0) { throw "Experiment compilation failed; see $output\compile.log" }

    $mockito = @($dependencyClasspath -split ';' | Where-Object { $_ -match '[\\/]mockito-core[\\/].*\.jar$' })
    if ($mockito.Count -ne 1) { throw 'Expected exactly one Mockito agent in the resolved test classpath.' }
    $testArguments = @('-o', ('-DargLine=-javaagent:"'+$mockito[0].Replace('\','/')+'" -Dfile.encoding=UTF-8'))
    if ($Postgres) {
        $testArguments += @('-Dunidr.u0.postgres=true','-Dtest=UniDrReceivingItemsSpikeTest')
        $logName = 'postgresql-test.log'
    } else {
        $testArguments += '-Dtest=UniDrDynamicReportsSpikeTest,UniDrReceivingItemsSpikeTest'
        $logName = 'h2-and-render-test.log'
    }
    $testArguments += 'surefire:test'
    & $MavenCommand @testArguments *> (Join-Path $output $logName)
    $testExit = $LASTEXITCODE
    Get-Content -LiteralPath (Join-Path $output $logName) -Tail 18
    if ($testExit -ne 0) { throw "U0 failed; see $output\$logName" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
