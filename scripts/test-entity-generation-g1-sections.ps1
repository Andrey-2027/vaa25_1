[CmdletBinding()]
param(
    [string]$RunId=('run-'+[guid]::NewGuid().ToString('N')),
    [string]$JavaHome='C:/Program Files/Axiom/AxiomJDK-21',
    [string]$Maven='C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd',
    [string]$LocalRepository='C:/Users/strim/.m2/repository'
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid RunId'}
$repo=Split-Path $PSScriptRoot -Parent
$output=Join-Path $repo "outputs/entity-generation-g1-extension/e4-a/$RunId"
if(Test-Path $output){throw 'Output already exists'}
New-Item -ItemType Directory -Path $output -Force|Out-Null
$temporary=[Collections.Generic.List[string]]::new();$modules=[Collections.Generic.List[string]]::new()
$oldJava=$env:JAVA_HOME
try{
    $env:JAVA_HOME=$JavaHome
    foreach($module in @('entity-design-core','entity-design-json','entity-design-runtime')){
        $directory=Join-Path $repo $module;$pom=Join-Path $directory ('.e4-a-'+[guid]::NewGuid().ToString('N')+'.pom.xml')
        $build=[Security.SecurityElement]::Escape((Join-Path $output "build-$module").Replace('\','/'))
        $source=[IO.File]::ReadAllText((Join-Path $directory 'pom.xml')).Replace('<build>',"<build><directory>$build</directory>")
        [IO.File]::WriteAllText($pom,$source,[Text.UTF8Encoding]::new($false));$temporary.Add($pom)
        $modules.Add('<module>'+[Security.SecurityElement]::Escape([IO.Path]::GetRelativePath($output,$pom).Replace('\','/'))+'</module>')
    }
    $reactor='<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>org.ipro.evidence</groupId><artifactId>g1-sections</artifactId><version>1</version><packaging>pom</packaging><modules>'+($modules -join '')+'</modules></project>'
    $reactorPath=Join-Path $output 'pom.xml';[IO.File]::WriteAllText($reactorPath,$reactor,[Text.UTF8Encoding]::new($false))
    & $Maven -o -B "-Dmaven.repo.local=$LocalRepository" -f $reactorPath "-Ddesign.sections.output=$(Join-Path $output 'input')" test *> (Join-Path $output 'tests.log')
    if($LASTEXITCODE -ne 0){throw "E4.A checks failed: $output/tests.log"}
    $results=foreach($report in Get-ChildItem $output -Recurse -Filter 'TEST-*.xml'){
        [xml]$xml=Get-Content $report.FullName -Raw;$s=$xml.testsuite
        if([int]$s.tests -le 0 -or [int]$s.failures -or [int]$s.errors -or [int]$s.skipped){throw 'Failed/skipped suite'}
        [ordered]@{suite=$s.name;tests=[int]$s.tests;failures=0;errors=0;skipped=0}
    }
    [IO.File]::WriteAllText((Join-Path $output 'test-results.json'),($results|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "E4.A neutral checks passed: $output"
}finally{
    foreach($path in $temporary){if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}}
    $env:JAVA_HOME=$oldJava
}
