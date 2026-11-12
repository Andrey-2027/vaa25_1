<#
.SYNOPSIS
  Plans and applies a change of an existing entity Java file (entity-design-source).

.DESCRIPTION
  plan  reads the entity file, builds a plan for the requested change and writes it to -Out; the project is not touched.
  apply puts a plan from -Plan into the project, if the files are still the ones the plan was made from.
  migrate-forms plans the transfer of legacy column arrays to a form configuration, preserving composition.

  The script only finds Java and Maven, makes sure the tool is built and starts it; the work is done by
  org.ipro.design.source.EntityModifyCli. Exit code: 0 done, 1 wrong usage, 2 files changed after the plan, 3 refused.

.EXAMPLE
  .\scripts\entity-modify.ps1 plan -Project C:\work\consumer -Type com.example.entitytypes.model.CustomCatalog `
      -AddField 'name=address,label=Address,type=STRING,length=200' -Out C:\work\plan1
  .\scripts\entity-modify.ps1 apply -Project C:\work\consumer -Plan C:\work\plan1
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)][ValidateSet('plan', 'apply', 'migrate-forms')][string]$Command,
    [Parameter(Mandatory = $true)][string]$Project,
    [string]$Type,
    [string[]]$AddField = @(),
    [string[]]$SetLabel = @(),
    [string]$Out,
    [string]$Plan,
    [string]$SourceRoot,
    # The class path of the project, one line. Without it the script asks Maven (dependency:build-classpath) in -Project.
    [string]$ClasspathFile,
    # Overrides; by default JAVA_HOME, MAVEN_HOME and PATH are searched.
    [string]$Java,
    [string]$Maven,
    [switch]$Offline,
    # Builds the tool again even when it is already built.
    [switch]$Rebuild
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Find-Tool([string]$Explicit, [string]$HomeVariable, [string[]]$Names, [string]$What) {
    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit)) { throw "$What not found: $Explicit" }
        return (Resolve-Path -LiteralPath $Explicit).Path
    }
    $toolHome = [Environment]::GetEnvironmentVariable($HomeVariable)
    if ($toolHome) {
        foreach ($name in $Names) {
            $candidate = Join-Path (Join-Path $toolHome 'bin') $name
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
    }
    foreach ($name in $Names) {
        $found = Get-Command $name -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($found) { return $found.Source }
    }
    throw "$What not found. Put it on PATH, set $HomeVariable, or pass it as a parameter."
}

$repository = Split-Path $PSScriptRoot -Parent
$javaExe = Find-Tool $Java 'JAVA_HOME' @('java.exe', 'java') 'Java'
$projectPath = (Resolve-Path -LiteralPath $Project).Path

# --- the tool -----------------------------------------------------------------------------------
$core = Join-Path $repository 'entity-design-core'
$source = Join-Path $repository 'entity-design-source'
$toolClasses = @((Join-Path $source 'target/classes'), (Join-Path $core 'target/classes'))
$mavenExe = $null
$built = (Test-Path -LiteralPath $toolClasses[0]) -and (Test-Path -LiteralPath $toolClasses[1])
if ($Rebuild -or -not $built) {
    $mavenExe = Find-Tool $Maven 'MAVEN_HOME' @('mvn.cmd', 'mvn') 'Maven'
    $offlineArgs = @(); if ($Offline) { $offlineArgs = @('-o') }
    & $mavenExe @offlineArgs -q -DskipTests -f (Join-Path $core 'pom.xml') install
    if ($LASTEXITCODE -ne 0) { throw 'Building entity-design-core failed.' }
    & $mavenExe @offlineArgs -q -DskipTests -f (Join-Path $source 'pom.xml') package
    if ($LASTEXITCODE -ne 0) { throw 'Building entity-design-source failed.' }
}
$toolClasspath = ($toolClasses -join [IO.Path]::PathSeparator)

# --- the class path of the project (plan only) ----------------------------------------------------
$temporaryClasspath = $null
$arguments = @($Command, '--project', $projectPath)
if ($SourceRoot) { $arguments += @('--source-root', $SourceRoot) }
if ($Command -eq 'plan') {
    if (-not $Type) { throw '-Type is required for plan.' }
    if (-not $Out) { throw '-Out is required for plan.' }
    if (-not $ClasspathFile) {
        if (-not $mavenExe) { $mavenExe = Find-Tool $Maven 'MAVEN_HOME' @('mvn.cmd', 'mvn') 'Maven' }
        $temporaryClasspath = [IO.Path]::GetTempFileName()
        $offlineArgs = @(); if ($Offline) { $offlineArgs = @('-o') }
        & $mavenExe @offlineArgs -q -f (Join-Path $projectPath 'pom.xml') dependency:build-classpath "-Dmdep.outputFile=$temporaryClasspath"
        if ($LASTEXITCODE -ne 0) { Remove-Item -LiteralPath $temporaryClasspath -Force; throw 'dependency:build-classpath failed; pass -ClasspathFile to skip it.' }
        $ClasspathFile = $temporaryClasspath
    }
    if (-not (Test-Path -LiteralPath $ClasspathFile)) { throw "The class path file does not exist: $ClasspathFile" }
    $arguments += @('--type', $Type, '--classpath-file', (Resolve-Path -LiteralPath $ClasspathFile).Path, '--out', $Out)
    foreach ($spec in $AddField) { $arguments += @('--add-field', $spec) }
    foreach ($spec in $SetLabel) { $arguments += @('--set-label', $spec) }
} elseif ($Command -eq 'migrate-forms') {
    if (-not $Type) { throw '-Type is required for migrate-forms.' }
    if (-not $Out) { throw '-Out is required for migrate-forms.' }
    if ($AddField.Count -or $SetLabel.Count -or $ClasspathFile -or $Plan) { throw 'migrate-forms accepts only -Project, -Type, -Out and optional -SourceRoot (plus tool options).' }
    $arguments += @('--type', $Type, '--out', $Out)
} else {
    if (-not $Plan) { throw '-Plan is required for apply.' }
    $arguments += @('--plan', $Plan)
}

# --- run ------------------------------------------------------------------------------------------
$previousEncoding = [Console]::OutputEncoding
$previousPreference = $ErrorActionPreference
try {
    [Console]::OutputEncoding = [Text.Encoding]::UTF8
    # The tool reports refusals on stderr; a caller that redirects it (2>&1) must not turn that into a terminating error here.
    $ErrorActionPreference = 'Continue'
    & $javaExe -cp $toolClasspath org.ipro.design.source.EntityModifyCli @arguments
    $code = $LASTEXITCODE
} finally {
    [Console]::OutputEncoding = $previousEncoding
    $ErrorActionPreference = $previousPreference
    if ($temporaryClasspath -and (Test-Path -LiteralPath $temporaryClasspath)) { Remove-Item -LiteralPath $temporaryClasspath -Force }
}
exit $code
