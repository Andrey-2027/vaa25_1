[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$MavenCommand,
    [Parameter(Mandatory = $true)][string]$JavaHome
)

# Scoped verification only. Full application compile/test remain separate gates.
# Only production UniDR and its tests are compiled, into their normal separate output
# directories. No other application classes are needed by this pure model package.
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
Push-Location (Split-Path -Parent $PSScriptRoot)
try {
    $javac = Join-Path $JavaHome 'bin\javac.exe'
    $version = & $javac -version 2>&1
    if ("$version" -notmatch '^javac 21\.') { throw "JDK 21 required; found $version" }
    $output = Join-Path (Get-Location) 'outputs\unidr-u1'
    New-Item -ItemType Directory -Force -Path $output,'target/classes','target/test-classes' | Out-Null
    & $MavenCommand -o dependency:build-classpath '-Dmdep.includeScope=test' `
        '-Dmdep.outputFile=outputs/unidr-u1/classpath.txt' *> (Join-Path $output 'dependencies.log')
    if ($LASTEXITCODE -ne 0) { throw "Classpath resolution failed; see $output\dependencies.log" }
    $classpath = (Get-Content -LiteralPath (Join-Path $output 'classpath.txt') -Raw).Trim()
    $arguments = @('--release','21','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),'-d','target/classes')
    foreach ($directory in @('src/main/java/org/ip/unidr/model','src/main/java/org/ip/unidr/source')) {
        $arguments += Get-ChildItem -LiteralPath $directory -Filter '*.java' | ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    }
    $argumentFile = Join-Path $output 'compile.args'
    [System.IO.File]::WriteAllLines($argumentFile,[string[]]$arguments,[System.Text.UTF8Encoding]::new($false))
    & $javac ('@'+$argumentFile) *> (Join-Path $output 'compile-scoped.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $output 'compile-scoped.log') -Tail 30; throw 'U1 compilation failed.' }
    $testClasspath = 'target/classes;' + $classpath
    $testArguments = @('--release','21','-encoding','UTF-8','-cp',('"'+$testClasspath.Replace('\','/')+'"'),'-d','target/test-classes')
    $testArguments += Get-ChildItem -LiteralPath 'src/test/java/org/ip/unidr/model' -Filter '*.java' |
        ForEach-Object { '"'+$_.FullName.Replace('\','/')+'"' }
    $testArgumentFile = Join-Path $output 'compile-tests.args'
    [System.IO.File]::WriteAllLines($testArgumentFile,[string[]]$testArguments,[System.Text.UTF8Encoding]::new($false))
    & $javac ('@'+$testArgumentFile) *> (Join-Path $output 'compile-tests.log')
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $output 'compile-tests.log') -Tail 30; throw 'U1 test compilation failed.' }
    & $MavenCommand -o '-DargLine=-Dfile.encoding=UTF-8' '-Dtest=UniDrDefinitionTest,UniDrDefinitionJsonCodecTest' `
        surefire:test *> (Join-Path $output 'test.log')
    $testExit = $LASTEXITCODE
    Get-Content (Join-Path $output 'test.log') -Tail 20
    if ($testExit -ne 0) { throw "U1 tests failed; see $output\test.log" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
