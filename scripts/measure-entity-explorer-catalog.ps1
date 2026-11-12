[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository',
    [string]$Output = 'docs/architecture/evidence/entity-registry-r0-inventory-20261110.json'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$registryRoot = Split-Path $PSScriptRoot -Parent
$registryJavaHome = $env:JAVA_HOME
Push-Location -LiteralPath $registryRoot
try {
    $env:JAVA_HOME = $JavaHome
    $registryOutput = Join-Path $registryRoot 'outputs/entity-registry-r0'
    New-Item -ItemType Directory -Force -Path $registryOutput | Out-Null
    & $Maven -o "-Dmaven.repo.local=$LocalRepository" dependency:build-classpath '-Dmdep.includeScope=test' `
        "-Dmdep.outputFile=$registryOutput/classpath.txt" *> "$registryOutput/dependencies.log"
    if ($LASTEXITCODE -ne 0) { throw 'Inventory classpath resolution failed; see dependencies.log.' }
    $registryClasspath = (Get-Content "$registryOutput/classpath.txt" -Raw).Trim()
    # Compile the fixture's transitive source closure, not stale application target/classes.
    # Avoid unrelated editor/UI sources; this gate measures facts, not whole-app acceptance.
    $registrySourceRoots = @('src/main/java') + @(Get-ChildItem -Directory | ForEach-Object {
        $candidate = Join-Path $_.FullName 'src/main/java'
        if (Test-Path -LiteralPath $candidate) { $candidate }
    })
    $registryCompile = @('--release', '21', '-encoding', 'UTF-8', '-cp',
        ('"' + $registryClasspath.Replace('\', '/') + '"'), '-sourcepath',
        ('"' + ($registrySourceRoots -join ';').Replace('\', '/') + '"'),
        '-d', 'outputs/entity-registry-r0', 'scripts/entity-explorer-pilot/EntityRegistryInventory.java')
    [IO.File]::WriteAllLines("$registryOutput/compile.args", [string[]]$registryCompile, [Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/javac.exe') ('@' + "$registryOutput/compile.args") *> "$registryOutput/compile.log"
    if ($LASTEXITCODE -ne 0) { throw 'Registry inventory compilation failed.' }
    $registryHead = (& git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify checkout HEAD.' }
    $registryStatus = @(& git status --porcelain=v1 --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot identify checkout changes.' }
    $registryStarted = [DateTimeOffset]::UtcNow.ToString('o')
    $registryRaw = Join-Path $registryOutput 'snapshot.json'
    $registryRun = @('-Dfile.encoding=UTF-8', '-cp', ('"' + "$registryOutput;$registryClasspath".Replace('\', '/') + '"'),
        'EntityRegistryInventory', ('"' + $registryRaw.Replace('\', '/') + '"'))
    [IO.File]::WriteAllLines("$registryOutput/run.args", [string[]]$registryRun, [Text.UTF8Encoding]::new($false))
    & (Join-Path $JavaHome 'bin/java.exe') ('@' + "$registryOutput/run.args") *> "$registryOutput/run.log"
    if ($LASTEXITCODE -ne 0) { throw 'Registry inventory measurement failed.' }
    Get-Content "$registryOutput/run.log" -Tail 1
    $registryFacts = Get-Content -LiteralPath $registryRaw -Raw | ConvertFrom-Json
    $registryEvidence = [ordered]@{
        schemaVersion = 1
        measuredAtUtc = $registryStarted
        head = $registryHead
        dirty = ($registryStatus.Count -gt 0)
        checkoutStatusBeforeMeasurement = $registryStatus
        command = "& ./scripts/measure-entity-explorer-catalog.ps1 -JavaHome '$($JavaHome.Replace("'", "''"))' -Maven '$($Maven.Replace("'", "''"))' -LocalRepository '$($LocalRepository.Replace("'", "''"))' -Output '$($Output.Replace("'", "''"))'"
        sourceHashes = @('scripts/entity-explorer-pilot/EntityRegistryInventory.java',
            'scripts/measure-entity-explorer-catalog.ps1') | ForEach-Object {
                [ordered]@{ path = $_; sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() }
            }
        inventory = $registryFacts
    }
    $registryDestination = [IO.Path]::GetFullPath((Join-Path $registryRoot $Output))
    New-Item -ItemType Directory -Force -Path (Split-Path $registryDestination -Parent) | Out-Null
    [IO.File]::WriteAllText($registryDestination, ($registryEvidence | ConvertTo-Json -Depth 40), [Text.UTF8Encoding]::new($false))
    Write-Output "Inventory evidence: $registryDestination"
}
finally {
    $env:JAVA_HOME = $registryJavaHome
    Pop-Location
}
