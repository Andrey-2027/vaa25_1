[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$DeliveryDirectory,
    [string]$RunId=('run-'+[guid]::NewGuid().ToString('N')),
    [string[]]$Cases=@('catalog-0','catalog-1','catalog-2','document-0','document-1','document-2','owned-catalog-2'),
    [string]$JavaHome='C:/Program Files/Axiom/AxiomJDK-21',
    [string]$Maven='C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd',
    [string]$LocalRepository='C:/Users/strim/.m2/repository',
    [string]$Settings,
    [string]$OutputDirectory,
    [switch]$UseInstalledPlatform
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
if($RunId -notmatch '^[A-Za-z0-9_-]+$'){throw 'Invalid RunId'}
$repo=Split-Path $PSScriptRoot -Parent
$output=if($OutputDirectory){[IO.Path]::GetFullPath($OutputDirectory)}else{Join-Path $repo "outputs/entity-generation-g1-extension/e4-b/$RunId"}
if(Test-Path $output){throw 'Output already exists'}
New-Item -ItemType Directory $output -Force|Out-Null
$delivery=[IO.Path]::GetFullPath($DeliveryDirectory)
$temporary=Join-Path $repo ('platform-vaadin/.e4-b-forms-'+[guid]::NewGuid().ToString('N')+'.pom.xml')
$oldJava=$env:JAVA_HOME
try{
    $env:JAVA_HOME=$JavaHome
    $build=[Security.SecurityElement]::Escape((Join-Path $output 'build-platform-vaadin').Replace('\','/'))
    $source=[IO.File]::ReadAllText((Join-Path $repo 'platform-vaadin/pom.xml'))
    if($source.Contains('<build>')){$source=$source.Replace('<build>',"<build><directory>$build</directory>")}
    else{$source=$source.Replace('</project>',"<build><directory>$build</directory></build></project>")}
    [IO.File]::WriteAllText($temporary,$source,[Text.UTF8Encoding]::new($false))
    $modules=[Collections.Generic.List[string]]::new()
    if(!$UseInstalledPlatform){$modules.Add([IO.Path]::GetRelativePath($output,$temporary).Replace('\','/'))}
    $bindings=[Collections.Generic.List[object]]::new()
    foreach($case in $Cases){
        if($case -notin @('catalog-0','catalog-1','catalog-2','document-0','document-1','document-2','owned-catalog-2')){throw 'Unknown case'}
        $project=Join-Path $output $case;New-Item -ItemType Directory $project -Force|Out-Null
        Copy-Item (Join-Path $repo 'scripts/entity-generation-g1/consumer/*') $project -Recurse
        $pom=[IO.File]::ReadAllText("$project/pom.xml").Replace('<artifactId>entity-generation-g1-harness</artifactId>',"<artifactId>entity-generation-g1-form-$case</artifactId>")
        $pom=[regex]::Replace($pom,'(?m)^    <dependencies>',"    <dependencies><dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><version>5.20.0</version><scope>test</scope></dependency>")
        [IO.File]::WriteAllText("$project/pom.xml",$pom,[Text.UTF8Encoding]::new($false))
        Copy-Item (Join-Path $repo "scripts/entity-generation-g1/scenarios/e4a-$case/ScenarioSpec.java") "$project/src/test/java/com/example/generated/harness/ScenarioSpec.java" -Force
        Copy-Item (Join-Path $repo 'scripts/entity-generation-g1/section-form-tests/GeneratedSectionFormsTest.java') "$project/src/test/java/com/example/generated/harness/"
        $manifest=Get-Content "$delivery/$case/manifest.json" -Raw|ConvertFrom-Json
        $entries=[Collections.Generic.Dictionary[string,byte[]]]::new([StringComparer]::Ordinal)
        if($manifest.entries.Count -eq 1){$file=Get-ChildItem "$delivery/$case" -Filter '*.java';$entries.Add($manifest.entries[0].relativePath,[IO.File]::ReadAllBytes($file.FullName))}
        else{
            $zip=[IO.Compression.ZipFile]::OpenRead("$delivery/$case/delivery.zip")
            try{foreach($entry in $zip.Entries){$stream=$entry.Open();$memory=[IO.MemoryStream]::new();try{$stream.CopyTo($memory);$entries.Add($entry.FullName,$memory.ToArray())}finally{$stream.Dispose();$memory.Dispose()}}}finally{$zip.Dispose()}
            if($entries.Count -ne $manifest.entries.Count+1){throw 'Unexpected ZIP entries'}
            $zipManifest=[Text.Encoding]::UTF8.GetString($entries['generation-manifest.json'])|ConvertFrom-Json
            if($zipManifest.manifestSha256 -ne $manifest.manifestSha256 -or $zipManifest.planId -ne $manifest.planId){throw 'Delivery manifest mismatch'}
        }
        foreach($entry in $manifest.entries){
            if($entry.relativePath -notmatch '^[A-Za-z_$][\w$]*(/[A-Za-z_$][\w$]*)*\.java$'){throw 'Invalid Java path'}
            $bytes=$entries[$entry.relativePath];$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
            if($hash -ne $entry.sha256 -or $bytes.Length -ne $entry.bytes){throw 'Delivery bytes mismatch'}
            $path=Join-Path "$project/src/main/java" $entry.relativePath;New-Item -ItemType Directory (Split-Path $path -Parent) -Force|Out-Null
            [IO.File]::WriteAllBytes($path,$bytes)
        }
        Copy-Item "$delivery/$case/manifest.json" "$project/delivery-manifest.json"
        $bindings.Add([ordered]@{case=$case;planId=$manifest.planId;manifestSha256=$manifest.manifestSha256;generatedFiles=$manifest.entries.Count})
        $modules.Add("$case/pom.xml")
    }
    $moduleXml=($modules|ForEach-Object {'<module>'+[Security.SecurityElement]::Escape($_)+'</module>'})-join ''
    $reactor='<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>org.ipro.evidence</groupId><artifactId>g1-section-forms</artifactId><version>1</version><packaging>pom</packaging><modules>'+$moduleXml+'</modules></project>'
    [IO.File]::WriteAllText("$output/pom.xml",$reactor,[Text.UTF8Encoding]::new($false))
    $agent='C:/Users/strim/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar'
    $mavenArgs=@('-B',"-Dmaven.repo.local=$LocalRepository",'-f',"$output/pom.xml",'-Pg1-references','-Dvaadin.skip=true','-Dtest=GeneratedSectionFormsTest','-Dsurefire.failIfNoSpecifiedTests=false',"-DargLine=-javaagent:$agent")
    if($Settings){$mavenArgs+=@('-s',$Settings)}else{$mavenArgs+='-o'}
    & $Maven @mavenArgs test *> "$output/tests.log"
    if($LASTEXITCODE){throw "Generated forms failed: $output/tests.log"}
    $results=foreach($case in $Cases){
        [xml]$xml=Get-Content "$output/$case/target/surefire-reports/TEST-com.example.generated.harness.GeneratedSectionFormsTest.xml" -Raw;$s=$xml.testsuite
        if([int]$s.tests -ne 4 -or [int]$s.failures -or [int]$s.errors -or [int]$s.skipped){throw "Failed/skipped suite: $case"}
        [ordered]@{case=$case;suite=$s.name;tests=[int]$s.tests;failures=0;errors=0;skipped=0}
    }
    [IO.File]::WriteAllText("$output/test-results.json",($results|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText("$output/delivery-bindings.json",($bindings|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "Generated E4.B forms passed: $output"
}finally{if(Test-Path -LiteralPath $temporary){Remove-Item -LiteralPath $temporary};$env:JAVA_HOME=$oldJava}
