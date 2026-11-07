#requires -Version 7.0
[CmdletBinding()]
param([Parameter(Mandatory)][string]$ProjectRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$ProjectRoot=[IO.Path]::GetFullPath($ProjectRoot)
if ($ProjectRoot.StartsWith($repo,[StringComparison]::OrdinalIgnoreCase)) { throw 'Only an external acceptance copy can be instrumented' }
$view=Join-Path $ProjectRoot 'src/main/java/org/ip/views/admin/EntityDraftEditorView.java'
$text=[IO.File]::ReadAllText($view)
if ($text.Contains('AcceptanceDownloadEvidence.capture')) { throw 'Editor adapter already installed' }
$needle='// Java delivery is not a JSON checkpoint. Never call session.exported here.'
if (($text.Split($needle)).Length -ne 2) { throw 'Unexpected download handler; review the adapter before using it' }
$text=$text.Replace($needle,"AcceptanceDownloadEvidence.capture(exported.plan());`n                    $needle")
[IO.File]::WriteAllText($view,$text,[Text.UTF8Encoding]::new($false))
Copy-Item "$PSScriptRoot/editor/AcceptanceDownloadEvidence.java" "$ProjectRoot/src/main/java/org/ip/views/admin/"
$application=Join-Path $ProjectRoot 'src/main/java/org/ip/Application.java'
$text=[IO.File]::ReadAllText($application)
$needle='@EntityScan({"org.ip.model"})'
if (!$text.Contains($needle)) { throw 'Unexpected editor EntityScan' }
[IO.File]::WriteAllText($application,$text.Replace($needle,'@EntityScan({"org.ip.model", "com.example"})'),[Text.UTF8Encoding]::new($false))
New-Item -ItemType Directory "$ProjectRoot/src/main/java/com/example" -Force | Out-Null
Copy-Item "$repo/scripts/entity-generation-g1/register-consumer/src/main/java/com/example/Item.java" "$ProjectRoot/src/main/java/com/example/Item.java"
Copy-Item "$repo/scripts/entity-generation-g1/register-consumer/src/main/java/com/example/Region.java" "$ProjectRoot/src/main/java/com/example/Region.java"
Write-Output 'Installed isolated download evidence adapter and consumer-owned Item/Region targets'
