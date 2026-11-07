#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RunId,
    [Parameter(Mandatory)][string]$StandRoot,
    [string]$JavaHome = 'C:/Program Files/Axiom/AxiomJDK-21',
    [string]$Maven = 'C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^\d{8}-\d{6}$') { throw 'Use a Moscow yyyyMMdd-HHmmss run id' }
$repo = Split-Path $PSScriptRoot -Parent
$out = Join-Path $repo "outputs/acceptance/em6-g1r5/$RunId"
$StandRoot = [IO.Path]::GetFullPath($StandRoot)
if ($StandRoot.StartsWith($repo, [StringComparison]::OrdinalIgnoreCase)) { throw 'Stand must be outside the source checkout' }
$project = Join-Path $StandRoot 'editor'
$repository = Join-Path $StandRoot 'repository'
New-Item -ItemType Directory -Path $out,$StandRoot -Force | Out-Null
if (Test-Path $project) { throw 'Use a fresh stand directory; preparation never overwrites a previous project' }
if (!(Test-Path $repository)) { throw 'Seed the isolated repository before preparation' }
$summary = [ordered]@{runId=$RunId;status='PREPARING';commit=(& git -C $repo rev-parse HEAD);project=$project;repository=$repository;modules=@();application=$null;acceptance=@{EM6='NOT_RUN';NONE='NOT_RUN';DAY='NOT_RUN'}}
function Json($path,$value) { [IO.File]::WriteAllText($path,($value | ConvertTo-Json -Depth 30)+"`n",[Text.UTF8Encoding]::new($false)) }
function Reports($directory) {
    @(Get-ChildItem $directory -Filter 'TEST-*.xml' -File | ForEach-Object {
        [xml]$report = Get-Content $_.FullName -Raw
        $s=$report.testsuite
        [ordered]@{name=[string]$s.name;tests=[int]$s.tests;failures=[int]$s.failures;errors=[int]$s.errors;skipped=[int]$s.skipped}
    })
}
$oldJava=$env:JAVA_HOME
try {
    $env:JAVA_HOME=$JavaHome
    & git -C $repo status --porcelain=v1 | Set-Content "$out/git-status-preparation.txt" -Encoding utf8
    & git -C $repo archive --format=zip "--output=$StandRoot/source.zip" HEAD
    if ($LASTEXITCODE) { throw 'git archive failed' }
    Expand-Archive -LiteralPath "$StandRoot/source.zip" -DestinationPath $project
    # Section-delivery regression tests deliberately consume local historical input fixtures.
    $fixture='docs/architecture/evidence/entity-generation-g1-extension-20261029/e4-a'
    foreach ($scenario in @('catalog-0','catalog-1','catalog-2','document-0','document-1','document-2','owned-catalog-2')) {
        $destination=Join-Path $project "$fixture/$scenario"
        New-Item -ItemType Directory $destination -Force | Out-Null
        Copy-Item -LiteralPath "$repo/$fixture/$scenario/input" -Destination $destination -Recurse
    }
    # Bootstrap contract checks resolve the four external source projects relative to the copy.
    $manifest=Get-Content "$repo/scripts/local-dependencies.json" -Raw | ConvertFrom-Json
    foreach ($dependency in @($manifest.projects | Where-Object { $_.relativePath.StartsWith('../') })) {
        foreach ($inputPath in $dependency.fingerprint.inputs) {
            $source=Join-Path (Join-Path $repo $dependency.relativePath) $inputPath
            $destination=Join-Path (Join-Path $project $dependency.relativePath) $inputPath
            New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
            Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
        }
    }
    # Include the exact reviewed working changes and stand adapters, including untracked files.
    foreach ($path in @('scripts/prepare-em6-g1r5.ps1','scripts/local-dependencies.json','scripts/em6-g1r5','scripts/entity-generation-g1/register-consumer')) {
        $source=Join-Path $repo $path
        if (Test-Path $source) { Copy-Item -LiteralPath $source -Destination (Join-Path $project (Split-Path $path -Parent)) -Recurse -Force }
    }
    foreach ($module in @('entity-design-core','entity-design-json','entity-design-source','entity-design-runtime','platform-register-vaadin')) {
        $log="$out/$module-build.log"
        & $Maven -B -ntp -o "-Dmaven.repo.local=$repository" -f "$project/$module/pom.xml" clean install *> $log
        $code=$LASTEXITCODE
        $rows=Reports "$project/$module/target/surefire-reports"
        $summary.modules+=@{module=$module;exitCode=$code;suites=$rows;log=$log}
        Json "$out/preparation-summary.json" $summary
        if ($code) { throw "Module failed: $module; $log" }
    }
    $tests=@('DraftEditorSessionTest','EntityDesignRuntimeProjectionIT','NomenclaturePriceFormTest','IndependentNoneRegisterFormTest','RegisterListFormToolbarTest','EntityModificationTest','PatchedModelParityTest','JavaEntityReaderParityTest','BootstrapScriptContractTest')
    $tests+=@(Get-ChildItem "$project/src/test/java" -Recurse -Filter 'EntityDraft*Test.java' -File | ForEach-Object BaseName)
    $tests=@($tests | Sort-Object -Unique)
    Push-Location $project
    try {
        & $Maven -B -ntp -o "-Dmaven.repo.local=$repository" '-Dvaadin.skip=true' "-Dtest=$($tests -join ',')" test *> "$out/application-tests.log"
        $code=$LASTEXITCODE
    } finally { Pop-Location }
    $summary.application=@{exitCode=$code;suites=(Reports "$project/target/surefire-reports");log="$out/application-tests.log"}
    if ($code) { throw 'Application checks failed; see application-tests.log' }
    $artifacts=@(Get-ChildItem "$repository/org/ipro" -Recurse -File -Include '*.jar','*.pom' | ForEach-Object {
        @{path=$_.FullName;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
    })
    Json "$out/dependency-artifacts.json" $artifacts
    $summary.status='COMPONENT_CHECKS_PASSED'
    $summary['remaining']=@('UI consumer startup','matching browser plan/context evidence','persistent H2 restart probe','freeze final baseline before browser acceptance')
} catch {
    $summary.status='PREPARATION_FAILED'
    $summary['error']=$_.Exception.Message
    throw
} finally {
    Json "$out/preparation-summary.json" $summary
    $env:JAVA_HOME=$oldJava
}
