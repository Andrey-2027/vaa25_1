[CmdletBinding()]
param(
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$Maven = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd'
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$taskRoot=Split-Path $PSScriptRoot -Parent
$taskOutput=Join-Path $taskRoot 'outputs/et8'
$et8Isolated=[IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('entity-types-et8-'+[Guid]::NewGuid().ToString('N'))))
if ($et8Isolated.StartsWith($taskRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase)) { throw 'Consumer must be outside checkout.' }
New-Item -ItemType Directory -Force $taskOutput | Out-Null
New-Item -ItemType Directory $et8Isolated | Out-Null
Copy-Item -LiteralPath (Join-Path $taskRoot 'examples/entity-types-consumer/pom.xml') -Destination $et8Isolated
Copy-Item -LiteralPath (Join-Path $taskRoot 'examples/entity-types-consumer/src') -Destination $et8Isolated -Recurse
$oldJava=$env:JAVA_HOME
Push-Location -LiteralPath $et8Isolated
try {
    $env:JAVA_HOME=$JavaHome
    $agent=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar'
    & $Maven -o '-Pbootstrap-smoke,production' ('-DargLine=-javaagent:'+$agent) package *> "$taskOutput/external-final.log"
    if ($LASTEXITCODE -ne 0) { Get-Content "$taskOutput/external-final.log" -Tail 40; throw 'External consumer failed.' }
    $jar=Join-Path $taskOutput 'external-consumer-exec.jar'
    Copy-Item -LiteralPath 'target/entity-types-consumer-exec.jar' -Destination $jar
    Copy-Item -LiteralPath 'target/surefire-reports' -Destination (Join-Path $taskOutput 'external-tests') -Recurse -Force
    [ordered]@{isolated=$et8Isolated; jar=$jar; built=$true; sha256=(Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash} |
        ConvertTo-Json | Set-Content "$taskOutput/external-receipt.json" -Encoding utf8
    Write-Output 'ET8 isolated binary consumer compiled, tested and packaged.'
} finally { $env:JAVA_HOME=$oldJava; Pop-Location }
