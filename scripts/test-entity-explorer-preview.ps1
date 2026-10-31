[CmdletBinding()]
param([string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21')
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$previewRoot = Split-Path $PSScriptRoot -Parent
$previewOutput = Join-Path $previewRoot 'outputs/entity-explorer-p7'
Push-Location -LiteralPath $previewRoot
try {
    # Reuse the existing isolated pilot overlay; no database or application startup in this gate.
    if (!(Test-Path 'outputs/entity-explorer-p1/local-classpath.txt') -or
        !(Test-Path 'outputs/entity-explorer-p2/classes/org/ip/views/admin/EntityExplorerView.class')) {
        throw 'Prepare the existing compact pilot overlay and P1 dependencies first.'
    }
    New-Item -ItemType Directory -Force -Path "$previewOutput/test-classes" | Out-Null
    $dependencies = (Get-Content outputs/entity-explorer-p1/local-classpath.txt -Raw).Trim()
    $classpath = 'outputs/entity-explorer-p2/classes;outputs/entity-explorer-p1/junit-launcher.jar;' + $dependencies
    $sources = @('src/main/java/org/ip/views/forms/ReceivingDocumentFormConfig.java',
        'src/main/java/org/ip/views/forms/NomenclatureItemForm.java',
        'src/main/java/org/ip/views/admin/ExplorerBehaviorPanel.java',
        'src/main/java/org/ip/views/admin/ExplorerFormPreview.java',
        'src/main/java/org/ip/views/admin/ExplorerFormPreviewDialog.java')
    $compile = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
        '-d','outputs/entity-explorer-p2/classes') + $sources
    [IO.File]::WriteAllLines("$previewOutput/compile.args", [string[]]$compile)
    & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$previewOutput/compile.args") *> "$previewOutput/compile.log"
    if ($LASTEXITCODE -ne 0) { throw 'Preview source compilation failed; see compile.log.' }
    $compile = @('--release','21','-parameters','-encoding','UTF-8','-cp',('"'+$classpath.Replace('\','/')+'"'),
        '-d','outputs/entity-explorer-p7/test-classes','scripts/entity-explorer-pilot/ExplorerStructureChecks.java',
        'src/test/java/org/ip/views/admin/ExplorerFormPreviewTest.java')
    [IO.File]::WriteAllLines("$previewOutput/test-compile.args", [string[]]$compile)
    & (Join-Path $JavaHome 'bin/javac.exe') ('@'+"$previewOutput/test-compile.args") *> "$previewOutput/test-compile.log"
    if ($LASTEXITCODE -ne 0) { throw 'Preview test compilation failed; see test-compile.log.' }
    $agent = @(Get-ChildItem outputs/entity-explorer-p1/deps -Recurse -Filter 'mockito-core-*.jar')
    if ($agent.Count -ne 1) { throw 'Expected one Mockito agent.' }
    & (Join-Path $JavaHome 'bin/java.exe') ('-javaagent:'+$agent[0].FullName) '-Dfile.encoding=UTF-8' `
        -cp ('outputs/entity-explorer-p7/test-classes;src/main/resources;'+$classpath) `
        ExplorerStructureChecks org.ip.views.admin.ExplorerFormPreviewTest *> "$previewOutput/tests.log"
    if ($LASTEXITCODE -ne 0) { throw 'Preview checks failed; see tests.log.' }
    & "$PSScriptRoot/run-entity-explorer-compact.ps1" -Action Test -JavaHome $JavaHome *> "$previewOutput/regression.log"
    Copy-Item -LiteralPath 'outputs/entity-explorer-p2/test.log' -Destination "$previewOutput/regression-tests.log" -Force
    Write-Output 'P7 preview and existing compact pilot checks passed.'
} finally { Pop-Location }
