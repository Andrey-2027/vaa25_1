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
$output=Join-Path $repo "outputs/entity-generation-g1-extension/e4-b/$RunId"
if(Test-Path $output){throw 'Output already exists'}
New-Item -ItemType Directory $output -Force|Out-Null
$tests=@('DraftOperationsTest','DraftValidatorTest','DocumentGenerationTest','ReferenceGenerationTest',
'CatalogJavaGeneratorTest','GenerationManifestTest','LegacyContextBinaryCompatibilityTest','RecipeContractTest','RecipeHandoffTest',
'OwnerGenerationTest','SectionGenerationTest','OwnerDraftJsonTest','DraftJsonCodecTest','CatalogOwnerFactsBridgeTest','RuntimeAdapterContractTest',
'DraftEditorSessionTest','EntityDraftEditorViewTest','EntityDraftJavaPreviewTest','EntityDraftJavaDownloadTest','EntityDraftJavaBundleTest',
'EntityDraftSectionDeliveryTest','EntityDraftSectionsTest','EntityDraftDocumentTest','EntityDraftReferenceTest','EntityDraftOwnerUiTest',
'EntityDraftJsonTransferTest','EntityDraftUxCompatibilityTest','EntityDraftCatalogAdapterTest','EntityDraftWorkspaceTest','EntityDraftWorkspaceContractTest',
'EntityDraftMatrixScenarioTest','CatalogOwnerFormsTest','ItemSectionHostPresenceTest','SectionPayloadTest','MetadataDrivenItemFormSaveAdapterTest',
'ItemFormInitialValuesTest','ItemFormLifecycleTest','ItemFormReadOnlySaveButtonTest','ItemFormSaveFlowTest')
$temporary=[Collections.Generic.List[string]]::new();$modules=[Collections.Generic.List[string]]::new()
$oldJava=$env:JAVA_HOME
try{
    $env:JAVA_HOME=$JavaHome
    foreach($module in @('entity-design-core','entity-design-json','entity-design-runtime','platform-vaadin','.')){
        $name=if($module -eq '.'){'app'}else{$module};$directory=Join-Path $repo $module
        $pom=Join-Path $directory ('.e4-b-'+[guid]::NewGuid().ToString('N')+'.pom.xml')
        $build=[Security.SecurityElement]::Escape((Join-Path $output "build-$name").Replace('\','/'))
        $source=[IO.File]::ReadAllText((Join-Path $directory 'pom.xml'))
        if($source.Contains('<build>')){$source=$source.Replace('<build>',"<build><directory>$build</directory>")}
        else{$source=$source.Replace('</project>',"<build><directory>$build</directory></build></project>")}
        [IO.File]::WriteAllText($pom,$source,[Text.UTF8Encoding]::new($false));$temporary.Add($pom)
        $modules.Add('<module>'+[Security.SecurityElement]::Escape([IO.Path]::GetRelativePath($output,$pom).Replace('\','/'))+'</module>')
    }
    $reactor='<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>org.ipro.evidence</groupId><artifactId>g1-section-ui</artifactId><version>1</version><packaging>pom</packaging><modules>'+($modules -join '')+'</modules></project>'
    $reactorPath=Join-Path $output 'pom.xml';[IO.File]::WriteAllText($reactorPath,$reactor,[Text.UTF8Encoding]::new($false))
    $agent="$LocalRepository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar"
    $fixtures=Join-Path $repo 'docs/architecture/evidence/entity-generation-g1-extension-20261029/e4-a'
    & $Maven -o -B "-Dmaven.repo.local=$LocalRepository" -f $reactorPath '-Dvaadin.skip=true' "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' "-DargLine=-javaagent:$agent" "-Ddesign.sections.fixtures=$fixtures" "-Ddesign.sections.delivery.output=$output/delivery" test *> "$output/tests.log"
    if($LASTEXITCODE){throw "E4.B checks failed: $output/tests.log"}
    $reports=@(Get-ChildItem $output -Recurse -Filter 'TEST-*.xml')
    $results=foreach($test in $tests){
        $files=@($reports|Where-Object Name -Like "TEST-*.$test.xml")
        if($files.Count -ne 1){throw "Expected exactly one report: $test"}
        [xml]$xml=Get-Content $files[0].FullName -Raw;$s=$xml.testsuite
        if([int]$s.tests -le 0 -or [int]$s.failures -or [int]$s.errors -or [int]$s.skipped){throw "Failed/skipped suite: $test"}
        [ordered]@{suite=$s.name;tests=[int]$s.tests;failures=0;errors=0;skipped=0}
    }
    [IO.File]::WriteAllText("$output/test-results.json",($results|ConvertTo-Json),[Text.UTF8Encoding]::new($false))
    Write-Output "E4.B UI checks passed: $output"
}finally{
    foreach($path in $temporary){if(Test-Path -LiteralPath $path){Remove-Item -LiteralPath $path}}
    $env:JAVA_HOME=$oldJava
}
