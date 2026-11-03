<#
.SYNOPSIS
  EM5: end-to-end acceptance of entity-modify on an isolated copy of examples/entity-types-consumer with a file H2 database.

.DESCRIPTION
  1. copies the consumer outside the checkout and puts a really generated catalog (GeneratedItem) into its model;
  2. first JVM (ET5_DDL=create) writes rows;
  3. entity-modify plan + apply change CustomCatalog, Product, ExtendedProduct, Order and GeneratedItem;
  4. every schema.sql is run on the database; the whole project is compiled;
  5. second JVM (ET5_DDL=validate) checks old rows, new fields, metadata, JDBC metadata, the foreign key and row-level scope;
  6. repeat apply, a hand edit between plan and apply, a second run of schema.sql and a clashing method are checked.

  PostgreSQL and the browser are not run. Only summary.json is kept (outputs/em5); the copy is removed unless -Keep.
#>
[CmdletBinding()]
param(
    [string]$Java,
    [string]$Maven,
    [string]$Summary,
    [switch]$Keep
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repository = Split-Path $PSScriptRoot -Parent
$rig = Join-Path $PSScriptRoot 'entity-modification'
if (-not $Summary) { $Summary = Join-Path $repository 'outputs/em5/summary.json' }

function Find-Tool([string]$Explicit, [string]$HomeVariable, [string[]]$Names, [string]$What) {
    if ($Explicit) { if (-not (Test-Path -LiteralPath $Explicit)) { throw "$What not found: $Explicit" }; return (Resolve-Path -LiteralPath $Explicit).Path }
    $toolHome = [Environment]::GetEnvironmentVariable($HomeVariable)
    if ($toolHome) { foreach ($name in $Names) { $candidate = Join-Path (Join-Path $toolHome 'bin') $name; if (Test-Path -LiteralPath $candidate) { return $candidate } } }
    foreach ($name in $Names) { $found = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1; if ($found) { return $found.Source } }
    throw "$What not found. Put it on PATH, set $HomeVariable, or pass it as a parameter."
}

$javaExe = Find-Tool $Java 'JAVA_HOME' @('java.exe', 'java') 'Java'
$mavenExe = Find-Tool $Maven 'MAVEN_HOME' @('mvn.cmd', 'mvn') 'Maven'
$host_ = (Get-Process -Id $PID).Path

function Quote([string]$Value) {
    if ($Value -eq '') { return '""' }
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + ($Value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

# Runs a program with its output captured as UTF-8; never throws on a non-zero exit.
function Invoke-Native([string]$File, [string[]]$Arguments, [string]$WorkingDirectory = $PWD.Path) {
    $info = New-Object Diagnostics.ProcessStartInfo
    $info.FileName = $File
    $info.Arguments = ($Arguments | ForEach-Object { Quote $_ }) -join ' '
    $info.WorkingDirectory = $WorkingDirectory
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.Encoding]::UTF8; $info.StandardErrorEncoding = [Text.Encoding]::UTF8
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    [pscustomobject]@{ Code = $process.ExitCode; Out = $stdout.Result; Err = $stderr.Result }
}

$scenarios = New-Object Collections.Generic.List[object]
function Record([string]$Name, [bool]$Passed, [string]$Detail) {
    $scenarios.Add([ordered]@{ scenario = $Name; status = $(if ($Passed) { 'PASS' } else { 'FAIL' }); detail = $Detail })
    Write-Host ('{0,-4} {1}  {2}' -f $(if ($Passed) { 'PASS' } else { 'FAIL' }), $Name, $Detail)
}

$work = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('entity-modification-em5-' + [Guid]::NewGuid().ToString('N'))))
if ($work.StartsWith($repository.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'The copy must be outside the checkout.' }
New-Item -ItemType Directory $work | Out-Null
$copy = Join-Path $work 'consumer'
$logs = Join-Path $work 'logs'
New-Item -ItemType Directory $copy, $logs | Out-Null
Copy-Item -LiteralPath (Join-Path $repository 'examples/entity-types-consumer/pom.xml') -Destination $copy
Copy-Item -LiteralPath (Join-Path $repository 'examples/entity-types-consumer/src') -Destination $copy -Recurse
$sources = Join-Path $copy 'src/main/java'
$model = Join-Path $sources 'com/example/entitytypes/model'
$packageName = 'com.example.entitytypes.model'
$database = (Join-Path $work 'db/em5') -replace '\\', '/'
$jdbc = "jdbc:h2:file:$database"
$failure = $null

function Invoke-Maven([string]$Name, [string[]]$Goals, [string]$Ddl) {
    $previous = @{ url = $env:ET5_JDBC_URL; ddl = $env:ET5_DDL }
    try {
        $env:ET5_JDBC_URL = $jdbc; if ($Ddl) { $env:ET5_DDL = $Ddl } else { $env:ET5_DDL = $null }
        $result = Invoke-Native $mavenExe (@('-o', '-B', '-Pbootstrap-smoke') + $Goals) $copy
        Set-Content -LiteralPath (Join-Path $logs "$Name.log") -Value ($result.Out + $result.Err) -Encoding UTF8
        return $result
    } finally { $env:ET5_JDBC_URL = $previous.url; $env:ET5_DDL = $previous.ddl }
}

# Calls entity-modify.ps1 in this session (a repeated -AddField needs an array, which -File cannot pass when a value has commas).
function Invoke-Modify([string[]]$Arguments) {
    $parameters = @{ Command = $Arguments[0]; Java = $javaExe; Maven = $mavenExe; Offline = $true }
    for ($i = 1; $i -lt $Arguments.Count; $i += 2) {
        $name = $Arguments[$i].TrimStart('-')
        if ($parameters.ContainsKey($name)) { $parameters[$name] = @($parameters[$name]) + $Arguments[$i + 1] }
        elseif ($name -in @('AddField', 'SetLabel')) { $parameters[$name] = @($Arguments[$i + 1]) }
        else { $parameters[$name] = $Arguments[$i + 1] }
    }
    $out = New-Object Collections.Generic.List[string]; $err = New-Object Collections.Generic.List[string]
    $previous = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        $global:LASTEXITCODE = 0
        & (Join-Path $PSScriptRoot 'entity-modify.ps1') @parameters 2>&1 | ForEach-Object {
            if ($_ -is [Management.Automation.ErrorRecord]) { $err.Add($_.ToString()) } else { $out.Add([string]$_) }
        }
        $code = $global:LASTEXITCODE
    } catch { $err.Add($_.Exception.Message); $code = 1 } finally { $ErrorActionPreference = $previous }
    [pscustomobject]@{ Code = $code; Out = ($out -join "`n"); Err = ($err -join "`n") }
}

function Sha([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash }

try {
    # --- the stand ---------------------------------------------------------------------------------
    $core = Join-Path $repository 'entity-design-core'
    $built = Invoke-Native $mavenExe @('-o', '-B', '-q', '-DskipTests', '-f', (Join-Path $core 'pom.xml'), 'install')
    if ($built.Code -ne 0) { throw "entity-design-core did not build:`n$($built.Out)$($built.Err)" }
    $generated = Invoke-Native $javaExe @('-cp', (Join-Path $core 'target/classes'), (Join-Path $rig 'GenerateCatalog.java'), $sources)
    Record 'EM5.1 real generated catalog put into the model' ($generated.Code -eq 0 -and (Test-Path (Join-Path $model 'GeneratedItem.java'))) ($generated.Out.Trim() + $generated.Err.Trim())
    if ($generated.Code -ne 0) { throw 'GenerateCatalog failed.' }

    $classpathFile = Join-Path $work 'classpath.txt'
    $classpath = Invoke-Maven 'classpath' @('-q', 'dependency:build-classpath', "-Dmdep.outputFile=$classpathFile") $null
    if ($classpath.Code -ne 0) { throw "dependency:build-classpath failed, see $logs" }
    $h2 = ((Get-Content -LiteralPath $classpathFile -Raw).Trim() -split [IO.Path]::PathSeparator | Where-Object { $_ -match '[\\/]h2-[\d.]+\.jar$' } | Select-Object -First 1)
    if (-not $h2) { throw 'The H2 jar is not on the class path of the copy.' }

    $testDirectory = Join-Path $copy 'src/test/java/com/example/entitytypes/em5'
    New-Item -ItemType Directory $testDirectory | Out-Null
    Copy-Item -LiteralPath (Join-Path $rig 'Em5Seed.java') -Destination $testDirectory

    # --- first JVM: create ---------------------------------------------------------------------------
    $first = Invoke-Maven 'seed' @('test', '-Dtest=Em5Seed') 'create'
    Record 'EM5.1 first JVM creates the schema and writes rows (ET5_DDL=create)' ($first.Code -eq 0) "log: seed.log"
    if ($first.Code -ne 0) { throw 'The first JVM failed.' }

    # --- the changes -----------------------------------------------------------------------------------
    $changes = @(
        @{ name = 'CustomCatalog'; add = @('name=address,label=Адрес,type=STRING,length=200'); labels = @('caption=Подпись (новая)'); sql = 'ALTER TABLE custom_catalog ADD COLUMN address VARCHAR(200);' },
        @{ name = 'Product'; add = @('name=price,label=Цена,type=DECIMAL,precision=15,scale=4'); labels = @(); sql = 'ALTER TABLE product ADD COLUMN price NUMERIC(15,4);' },
        @{ name = 'ExtendedProduct'; add = @("name=baseProduct,label=Основной товар,type=REFERENCE,reference=$packageName.Product"); labels = @(); sql = 'FOREIGN KEY (base_product_id) REFERENCES product (id);' },
        @{ name = 'Order'; add = @('name=dueDate,label=Срок,type=DATE'); labels = @(); sql = 'ALTER TABLE et5_order ADD COLUMN due_date DATE;' },
        @{ name = 'GeneratedItem'; add = @('name=note,label=Заметка,type=STRING,length=150', 'name=quantity,label=Количество,type=LONG', 'name=active,label=Активно,type=BOOLEAN', 'name=checkedAt,label=Проверено,type=DATETIME'); labels = @(); sql = 'ALTER TABLE generated_item ADD COLUMN checked_at TIMESTAMP(6);' }
    )
    foreach ($change in $changes) {
        $planDirectory = Join-Path $work ('plans/' + $change.name)
        $arguments = @('plan', '-Project', $copy, '-Type', "$packageName.$($change.name)", '-Out', $planDirectory, '-ClasspathFile', $classpathFile)
        foreach ($spec in $change.add) { $arguments += @('-AddField', $spec) }
        foreach ($spec in $change.labels) { $arguments += @('-SetLabel', $spec) }
        $plan = Invoke-Modify $arguments
        $schemaFile = Join-Path $planDirectory 'schema.sql'
        $schema = if (Test-Path -LiteralPath $schemaFile) { Get-Content -LiteralPath $schemaFile -Raw -Encoding UTF8 } else { '' }
        Record "EM5.2 plan $($change.name)" ($plan.Code -eq 0 -and $schema.Contains($change.sql)) "exit $($plan.Code); $(($schema -split "`n" | Select-Object -First 1).Trim())"
        if ($plan.Code -ne 0) { throw "plan for $($change.name) failed:`n$($plan.Out)$($plan.Err)" }
        $applied = Invoke-Modify @('apply', '-Project', $copy, '-Plan', $planDirectory)
        Record "EM5.2 apply $($change.name)" ($applied.Code -eq 0 -and $applied.Out.StartsWith('APPLIED')) "exit $($applied.Code)"
        if ($applied.Code -ne 0) { throw "apply for $($change.name) failed:`n$($applied.Out)$($applied.Err)" }
    }

    # --- SQL on the database -------------------------------------------------------------------------------
    foreach ($change in $changes) {
        $script = Join-Path $work ('plans/' + $change.name + '/schema.sql')
        $ran = Invoke-Native $javaExe @('-cp', $h2, 'org.h2.tools.RunScript', '-url', $jdbc, '-user', 'sa', '-script', $script)
        Record "EM5.2 schema.sql of $($change.name) runs on the database" ($ran.Code -eq 0) "exit $($ran.Code) $($ran.Err.Trim())"
        if ($ran.Code -ne 0) { throw 'schema.sql failed.' }
    }
    $again = Invoke-Native $javaExe @('-cp', $h2, 'org.h2.tools.RunScript', '-url', $jdbc, '-user', 'sa', '-script', (Join-Path $work 'plans/CustomCatalog/schema.sql'))
    Record 'EM5.4 a second run of schema.sql fails' ($again.Code -ne 0) ($again.Err.Trim() -split "`n" | Select-Object -First 1)

    # --- the whole project builds; second JVM: validate -------------------------------------------------------
    Copy-Item -LiteralPath (Join-Path $rig 'Em5Verify.java') -Destination $testDirectory
    $compiled = Invoke-Maven 'compile' @('test-compile') $null
    Record 'EM5.2 the whole patched project compiles, tests included' ($compiled.Code -eq 0) 'log: compile.log'
    if ($compiled.Code -ne 0) { throw 'The patched project does not compile.' }
    $second = Invoke-Maven 'verify' @('test', '-Dtest=Em5Verify') 'validate'
    $tests = if ($second.Out -match 'Tests run: (\d+), Failures: (\d+), Errors: (\d+)') { "tests $($Matches[1]), failures $($Matches[2]), errors $($Matches[3])" } else { 'no test summary' }
    Record 'EM5.2/3/4 second JVM on the same database (ET5_DDL=validate): rows, types, FK, metadata, scope' ($second.Code -eq 0) $tests
    if ($second.Code -ne 0) { Write-Host (($second.Out -split "`n" | Select-Object -Last 40) -join "`n") }

    # --- refusals and conflicts ---------------------------------------------------------------------------
    $repeat = Invoke-Modify @('apply', '-Project', $copy, '-Plan', (Join-Path $work 'plans/Order'))
    Record 'EM5.4 a repeated apply is NOOP' ($repeat.Code -eq 0 -and $repeat.Out.StartsWith('NOOP')) "exit $($repeat.Code)"

    $catalog = Join-Path $model 'CustomCatalog.java'
    $conflictPlan = Join-Path $work 'plans/CustomCatalogZip'
    $zip = @('plan', '-Project', $copy, '-Type', "$packageName.CustomCatalog", '-Out', $conflictPlan, '-ClasspathFile', $classpathFile, '-AddField', 'name=zip,label=Индекс,type=STRING,length=10')
    $planned = Invoke-Modify $zip
    Add-Content -LiteralPath $catalog -Value '// edited by hand between plan and apply' -Encoding UTF8
    $handEdited = Sha $catalog
    $conflict = Invoke-Modify @('apply', '-Project', $copy, '-Plan', $conflictPlan)
    Record 'EM5.4 a hand edit between plan and apply is a conflict and the file stays whole' ($planned.Code -eq 0 -and $conflict.Code -eq 2 -and (Sha $catalog) -eq $handEdited) "exit $($conflict.Code)"
    $replanned = Invoke-Modify $zip
    $reapplied = Invoke-Modify @('apply', '-Project', $copy, '-Plan', $conflictPlan)
    $text = Get-Content -LiteralPath $catalog -Raw -Encoding UTF8
    Record 'EM5.4 a new plan after the hand edit keeps it' ($replanned.Code -eq 0 -and $reapplied.Code -eq 0 -and $text.Contains('// edited by hand between plan and apply') -and $text.Contains('private String zip;')) "exit $($replanned.Code)/$($reapplied.Code)"

    $extended = Join-Path $model 'ExtendedProduct.java'
    $content = Get-Content -LiteralPath $extended -Raw -Encoding UTF8
    $index = $content.LastIndexOf('}')
    [IO.File]::WriteAllText($extended, $content.Substring(0, $index) + "    public void setWeight(Object value) { }`n}`n", (New-Object Text.UTF8Encoding $false))
    $clash = Sha $extended
    $refused = Invoke-Modify @('plan', '-Project', $copy, '-Type', "$packageName.ExtendedProduct", '-Out', (Join-Path $work 'plans/Clash'), '-ClasspathFile', $classpathFile, '-AddField', 'name=weight,type=INTEGER')
    Record 'EM5.4 a field that clashes with a hand-written method is refused before anything is written' ($refused.Code -eq 3 -and $refused.Err.Contains('RESERVED_FIELD') -and (Sha $extended) -eq $clash -and -not (Test-Path (Join-Path $work 'plans/Clash'))) "exit $($refused.Code)"

    $final = Invoke-Maven 'compile-final' @('test-compile') $null
    Record 'EM5.4 the project still compiles after the second round of changes' ($final.Code -eq 0) 'log: compile-final.log'
} catch {
    $failure = $_.Exception.Message
    Write-Host "STOPPED: $failure" -ForegroundColor Red
} finally {
    $failed = @($scenarios | Where-Object { $_.status -ne 'PASS' }).Count
    $overall = if ($failure -or $failed -gt 0) { 'FAIL' } else { 'PASS' }
    New-Item -ItemType Directory -Force (Split-Path $Summary) | Out-Null
    [ordered]@{
        suite = 'entity-modification-em5'; status = $overall; stopped = $failure; scenarios = $scenarios
        'PostgreSQL' = 'NOT_RUN'; browser = 'NOT_RUN'; database = 'H2 file, two JVM runs'
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Summary -Encoding UTF8
    Write-Host "EM5 $overall ($($scenarios.Count) scenarios, $failed failed). Summary: $Summary"
    if ($Keep) { Write-Host "Copy kept: $work" }
    elseif (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
}
# The exit code of the last native call (a refusal is expected in the scenarios) must not become the exit code of the suite.
if ($overall -ne 'PASS') { exit 1 }
exit 0
