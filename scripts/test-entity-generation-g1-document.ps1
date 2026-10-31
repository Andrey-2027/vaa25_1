[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = (Join-Path $env:USERPROFILE '.m2/repository'),
    [string]$RunId = ('run-' + [guid]::NewGuid().ToString('N')),
    [switch]$IncludeOwnerForms
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[A-Za-z0-9_-]+$') { throw 'RunId must be a single portable directory name.' }
$repo = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repo "outputs/entity-generation-g1-extension/e1-ab/$RunId"
if (Test-Path -LiteralPath $output) { throw "Run directory already exists: $output" }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$temporaryPom = Join-Path $repo ('.g1-document-' + [guid]::NewGuid().ToString('N') + '.pom.xml')
$previousJavaHome = $env:JAVA_HOME
$tests = @(
    'DraftOperationsTest','DraftValidatorTest','DocumentGenerationTest','ReferenceGenerationTest','CatalogJavaGeneratorTest',
    'GenerationManifestTest','LegacyContextBinaryCompatibilityTest','RecipeContractTest','RecipeHandoffTest',
    'OwnerGenerationTest','OwnerDraftJsonTest','OwnerAcceptanceExportTest','CatalogOwnerFactsBridgeTest',
    'DraftJsonCodecTest','RuntimeAdapterContractTest','DraftEditorSessionTest','EntityDraftEditorViewTest',
    'EntityDraftJavaPreviewTest','EntityDraftJavaDownloadTest','EntityDraftJsonTransferTest','EntityDraftSectionsTest',
    'EntityDraftUxCompatibilityTest','EntityDraftCatalogAdapterTest','EntityDraftWorkspaceTest',
    'EntityDraftWorkspaceContractTest','EntityDraftDocumentTest','EntityDraftReferenceTest'
)
if ($IncludeOwnerForms) {
    $tests += @('EntityDraftOwnerUiTest','CatalogOwnerFormsTest','CatalogOwnerListSelectionTest','CatalogOwnerExplorerTest','EntityExplorerPilotPanelTest','VaadinModuleCompositionTest',
        'ItemFormInitialValuesTest','ItemFormLifecycleTest','ItemFormReadOnlySaveButtonTest',
        'ItemFormSaveFlowTest','ListFormContextTest','SelectionFormContextTest')
}
function Xml-Text([string]$value) { return [Security.SecurityElement]::Escape($value.Replace('\','/')) }
function Get-RelativeDirectory([string]$from, [string]$to) {
    $fromPath = ([IO.Path]::GetFullPath($from).TrimEnd('\') + '\')
    $toPath = [IO.Path]::GetFullPath($to)
    $fromUri = [Uri]::new($fromPath)
    $toUri = [Uri]::new($toPath)
    return [Uri]::UnescapeDataString($fromUri.MakeRelativeUri($toUri).ToString()).Replace('/', '\')
}
Push-Location -LiteralPath $repo
try {
    $env:JAVA_HOME = $JavaHome
    $directory = Xml-Text (Join-Path $output 'build-ui')
    $app = [IO.File]::ReadAllText((Join-Path $repo 'pom.xml')).Replace('<build>', "<build><directory>$directory</directory>")
    [IO.File]::WriteAllText($temporaryPom, $app, [Text.UTF8Encoding]::new($false))
    $modules = @('entity-design-core','entity-design-json','entity-design-runtime','platform-vaadin') | ForEach-Object {
        '<module>' + (Xml-Text (Get-RelativeDirectory $output (Join-Path $repo $_))) + '</module>'
    }
    $modules += '<module>' + (Xml-Text (Get-RelativeDirectory $output $temporaryPom)) + '</module>'
    $reactor = '<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion><groupId>org.ipro.evidence</groupId><artifactId>g1-document-check</artifactId><version>1</version><packaging>pom</packaging><modules>' + ($modules -join '') + '</modules></project>'
    $reactorPath = Join-Path $output 'pom.xml'
    [IO.File]::WriteAllText($reactorPath, $reactor, [Text.UTF8Encoding]::new($false))
    $agent = Join-Path $LocalRepository 'org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar'
    if (!(Test-Path -LiteralPath $agent)) { throw "Mockito startup agent not found: $agent" }
    # Reactor consumes current core/json/runtime without install or shared SNAPSHOT cache updates.
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" -f $reactorPath '-Dvaadin.skip=true' `
        "-Dtest=$($tests -join ',')" '-Dsurefire.failIfNoSpecifiedTests=false' `
        "-DargLine=-javaagent:$($agent.Replace('\','/'))" `
        "-Ddesign.document.output=$(Join-Path $output 'input')" `
        "-Ddesign.reference.output=$(Join-Path $output 'reference-input')" `
        "-Ddesign.owner.output=$(Join-Path $output 'owner-input')" `
        "-Ddesign.owner.acceptance.output=$(Join-Path $output 'owner-acceptance-input')" test *> (Join-Path $output 'tests.log')
    if ($LASTEXITCODE -ne 0) { throw "Document checks failed: $output/tests.log" }
    $reportRoots = @('entity-design-core/target/surefire-reports','entity-design-json/target/surefire-reports',
        'entity-design-runtime/target/surefire-reports','platform-vaadin/target/surefire-reports') | ForEach-Object { Join-Path $repo $_ }
    $reportRoots += Join-Path $output 'build-ui/surefire-reports'
    $results = foreach ($test in $tests) {
        $reports = @($reportRoots | ForEach-Object { Get-ChildItem -LiteralPath $_ -Filter "TEST-*.$test.xml" })
        if ($reports.Count -ne 1) { throw "Expected one report for $test, found $($reports.Count)" }
        [xml]$xml = Get-Content -LiteralPath $reports[0].FullName -Raw
        $suite = $xml.testsuite
        if ([int]$suite.tests -le 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
            throw "Suite did not pass without skips: $test"
        }
        [ordered]@{suite=$test; tests=[int]$suite.tests; failures=[int]$suite.failures; errors=[int]$suite.errors; skipped=[int]$suite.skipped}
    }
    [IO.File]::WriteAllText((Join-Path $output 'test-results.json'), ($results | ConvertTo-Json -Depth 5), [Text.UTF8Encoding]::new($false))
    Write-Output "E1.A/B document and legacy editor checks passed: $output"
} finally {
    if (Test-Path -LiteralPath $temporaryPom) { Remove-Item -LiteralPath $temporaryPom }
    $env:JAVA_HOME = $previousJavaHome
    Pop-Location
}
