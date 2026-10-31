#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository',
    [string]$OutputDirectory = 'outputs/entity-explorer-p8-0'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$contractRoot = Split-Path $PSScriptRoot -Parent
$moduleRoot = Join-Path $contractRoot 'entity-design-core'
$resources = Join-Path $moduleRoot 'src/main/resources/org/ipro/design'
$fixtures = Join-Path $moduleRoot 'src/test/resources/org/ipro/design/fixtures'
$output = Join-Path $contractRoot $OutputDirectory
New-Item -ItemType Directory -Force -Path $output | Out-Null
$schema = Join-Path $resources 'schema/entity-draft-v1.schema.json'
$checks = [System.Collections.Generic.List[object]]::new()
function Check([string]$name, [bool]$condition) {
    if (!$condition) { throw "Contract check failed: $name" }
    $checks.Add([ordered]@{name=$name;passed=$true})
}
function Copy-Draft($value) { return ($value | ConvertTo-Json -Depth 64 | ConvertFrom-Json -AsHashtable) }
function Envelope($value) {
    return [bool](Test-Json -Json ($value | ConvertTo-Json -Depth 64 -Compress) -SchemaFile $schema -ErrorAction SilentlyContinue)
}
$complete = Get-Content -LiteralPath "$fixtures/catalog-two-sections.json" -Raw | ConvertFrom-Json -AsHashtable
$unfinished = Get-Content -LiteralPath "$fixtures/unfinished.json" -Raw | ConvertFrom-Json -AsHashtable
$semantic = Get-Content -LiteralPath "$fixtures/semantic-errors.json" -Raw | ConvertFrom-Json -AsHashtable
Check 'complete envelope' (Envelope $complete)
Check 'unfinished values remain exportable' (Envelope $unfinished)
Check 'semantic errors and raw numeric text remain exportable' (Envelope $semantic)
$candidate = Copy-Draft $complete; $candidate.schemaVersion=2
Check 'unknown envelope version is rejected' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.recipe.id='document-v1'
Check 'unsupported recipe is rejected' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.recipe.version=2
Check 'unsupported recipe version is rejected' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.extra='not portable'
Check 'unknown root property is rejected' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.fields[0].extra='not portable'
Check 'unknown nested property is rejected' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.entity.id=42
Check 'business identity is not part of the entity definition' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.fields[1].constraints.precision=12
Check 'numeric constraint is lexical input rather than a JSON number' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.fields[0].type='FUTURE_TYPE'
Check 'unknown field type survives envelope parsing for semantic diagnostics' (Envelope $candidate)
$candidate = Copy-Draft $complete; $candidate.entity.tableName.text='stale_auto_value'
Check 'AUTO cannot hide a stale persisted suggestion' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.entity.tableName.mode='EXPLICIT'; $candidate.entity.tableName.text=''
Check 'explicit unfinished value remains explicit' (Envelope $candidate)
$candidate = Copy-Draft $complete; $candidate.sections=@(1..51 | ForEach-Object { Copy-Draft $complete.sections[0] })
Check 'section envelope limit' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.fields[0].id='builtin:code'
Check 'inherited identity cannot masquerade as a user field ID' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.entity.label='x' * 4097
Check 'lexical value limit' (!(Envelope $candidate))
$candidate = Copy-Draft $complete; $candidate.fields=@(1..1001 | ForEach-Object { Copy-Draft $complete.fields[0] })
Check 'field array envelope limit' (!(Envelope $candidate))
$roundTrip = Copy-Draft $semantic
Check 'raw invalid numeric text preserved by portable JSON' ($roundTrip.fields[1].constraints.precision -ceq '12x' -and $roundTrip.fields[1].constraints.scale -ceq '-')
Check 'field and section order and IDs preserved by portable JSON' (($roundTrip.sections.id -join ',') -ceq ($semantic.sections.id -join ',') -and ($roundTrip.fields.id -join ',') -ceq ($semantic.fields.id -join ','))

$recipe = Get-Content -LiteralPath "$resources/recipes/standard-catalog-v1.json" -Raw | ConvertFrom-Json
$baseSource = Get-Content -LiteralPath "$contractRoot/platform-core/src/main/java/org/ipro/crud/StandardCatalogEntity.java" -Raw
$baseFields = [regex]::Matches($baseSource, '(?s)@Size\(max = (?<max>\d+)\).*?@Column\(name = "(?<column>\w+)", nullable = false, length = (?<length>\d+)\).*?private String (?<field>\w+);')
Check 'two inherited field declarations are confirmed by the current source' ($baseFields.Count -eq 2)
foreach ($field in $recipe.inheritedFields) {
    $actual = @($baseFields | Where-Object { $_.Groups['field'].Value -ceq $field.name })
    Check ("inherited length and column: " + $field.name) ($actual.Count -eq 1 -and [int]$actual[0].Groups['max'].Value -eq $field.length -and [int]$actual[0].Groups['length'].Value -eq $field.length -and $actual[0].Groups['column'].Value -ceq $field.name)
}
Check 'numbering is a role declaration, without reserving a value' ($baseSource -match '@Numbered\(role = NumberingRole\.CATALOG_CODE\)')
$surface = Get-Content -LiteralPath "$contractRoot/src/test/resources/platform-core-surface.txt" -Raw
Check 'historical base role at capture stays INTERNAL' ($recipe.baseApiRoleAtCapture -ceq 'INTERNAL')
Check 'current standard catalog prerequisite is APP_API' ($surface -match '(?m)^APP_API org\.ipro\.crud\.StandardCatalogEntity\r?$')
Check 'current standard document prerequisite is APP_API' ($surface -match '(?m)^APP_API org\.ipro\.crud\.StandardDocumentEntity\r?$')
Check 'capture generation stays disabled after API promotion' (!$recipe.generationEnabled)
$nextRecipe=Get-Content -LiteralPath "$resources/recipes/standard-catalog-v2.json" -Raw | ConvertFrom-Json
Check 'new recipe has an explicit distinct reference without an envelope meaning change' ($nextRecipe.id -ceq 'standard-catalog-v2' -and $nextRecipe.version -eq 2 -and $nextRecipe.schemaVersion -eq 1 -and !$nextRecipe.upgradesAutomatically)
Check 'new recipe does not enable file generation from APP_API alone' ($nextRecipe.baseApiRoleAtCapture -ceq 'APP_API' -and !$nextRecipe.generationEnabled -and $nextRecipe.g1Prerequisites.Count -eq 6)
$sectionSource = Get-Content -LiteralPath "$contractRoot/platform-contracts/src/main/java/org/ipro/metadata/annotation/TableSectionMetadata.java" -Raw
Check 'owned section policies match current platform declarations' ($sectionSource -match ('default SectionPersistenceMode\.' + $recipe.section.persistence) -and $sectionSource -match ('default SectionRlsPolicy\.' + $recipe.section.rlsPolicy))
[xml]$pom = Get-Content -LiteralPath "$moduleRoot/pom.xml" -Raw
Check 'standalone core has no application parent or runtime dependency' ($null -eq $pom.SelectSingleNode('/*[local-name()="project"]/*[local-name()="parent"]') -and $pom.SelectNodes('//*[local-name()="dependency"][not(*[local-name()="scope"]="test")]').Count -eq 0)

$manifest = Get-Content -LiteralPath "$contractRoot/scripts/local-dependencies.json" -Raw | ConvertFrom-Json
$project = @($manifest.projects | Where-Object id -eq 'entity-design-core')
Check 'bootstrap knows the independent core exactly once' ($project.Count -eq 1 -and $project[0].relativePath -ceq 'entity-design-core' -and $project[0].dependsOn.Count -eq 0)
$project = $project[0]
$fingerprintText = [Text.StringBuilder]::new()
$files = [System.Collections.Generic.SortedDictionary[string,string]]::new([StringComparer]::Ordinal)
foreach ($inputPath in $project.fingerprint.inputs) {
    foreach ($file in (Get-ChildItem -LiteralPath (Join-Path $moduleRoot $inputPath) -Recurse -File)) {
        $relative = [IO.Path]::GetRelativePath($moduleRoot,$file.FullName).Replace('\','/')
        if ($relative -notmatch '(^|/)(target|node_modules|\.git|\.idea|\.freebuff)/') { $files[$relative]=$file.FullName }
    }
}
foreach ($path in $files.Keys) {
    [void]$fingerprintText.Append($path).Append("`0").Append((Get-FileHash -LiteralPath $files[$path] -Algorithm SHA256).Hash.ToLowerInvariant()).Append("`n")
}
$digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($fingerprintText.ToString()))).ToLowerInvariant()
Check 'bootstrap fingerprint matches reviewed core sources' ($project.fingerprint.algorithm -ceq 'gitvaa-source-tree-sha256-v2' -and $project.fingerprint.sha256 -ceq $digest -and $project.fingerprint.fileCount -eq $files.Count)
$previousJavaHome = $env:JAVA_HOME
try {
    if (!$SkipBuild) {
        $env:JAVA_HOME=$JavaHome
        & $Maven -o ("-Dmaven.repo.local=$LocalRepository") -f "$moduleRoot/pom.xml" clean verify *> "$output/module-build.log"
        Check 'independent Maven verify' ($LASTEXITCODE -eq 0)
        $archive = [IO.Compression.ZipFile]::OpenRead("$moduleRoot/target/entity-design-core-1.0-SNAPSHOT.jar")
        try {
            Check 'published schema and recipe are packaged' ($null -ne $archive.GetEntry('org/ipro/design/schema/entity-draft-v1.schema.json') -and $null -ne $archive.GetEntry('org/ipro/design/recipes/standard-catalog-v1.json'))
        } finally { $archive.Dispose() }
    }
} finally { $env:JAVA_HOME=$previousJavaHome }
$resultsName = if ($SkipBuild) { 'contract-results-source-only.json' } else { 'contract-results.json' }
[IO.File]::WriteAllText("$output/$resultsName", ($checks | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
Write-Output ("P8.0 contract checks passed: " + $checks.Count)
