[CmdletBinding()]
param(
    [ValidateSet('P0','P1','PU','P2','P3','P4','P5','P6','P7','P8')][string]$Stage = 'P8',
    [switch]$Regression,
    [string[]]$AdditionalTestClasses = @(),
    [ValidatePattern('^[a-zA-Z0-9-]+\.log$')][string]$TestLogName = 'test-run.log',
    [string]$MavenCommand = 'C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd',
    [string]$JavaHome = 'C:\Program Files\Axiom\AxiomJDK-21',
    [string]$LocalRepository = 'C:\Users\strim\.m2\repository'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$requestedStage = $Stage
if ($Stage -eq 'P5') { $AdditionalTestClasses += 'org.ip.application.access.PermissionWorkspaceP5IT' }
if ($Stage -in @('P6','P7','P8')) { $AdditionalTestClasses += @('org.ip.application.access.PermissionWorkspaceP5IT','org.ip.application.access.PermissionMatrixP6IT') }
if ($Stage -in @('P7','P8')) { $AdditionalTestClasses += 'org.ip.application.access.PermissionScopeEditorIT' }
if ($Stage -in @('P5','P6','P7','P8')) { $Stage = 'P4' }
$p0Root = Split-Path -Parent $PSScriptRoot
$p0Output = Join-Path $p0Root ('outputs/permissions-' + $requestedStage.ToLowerInvariant())
New-Item -ItemType Directory -Force -Path $p0Output | Out-Null
$p0PreviousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
Push-Location -LiteralPath $p0Root
try {
    $ErrorActionPreference = 'Continue'
    & $MavenCommand -o "-Dmaven.repo.local=$LocalRepository" dependency:build-classpath `
        '-Dmdep.includeScope=test' ("-Dmdep.outputFile=outputs/permissions-" + $requestedStage.ToLowerInvariant() + '/classpath.txt') `
        *> (Join-Path $p0Output 'dependencies.log')
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { throw "$Stage classpath resolution failed; inspect dependencies.log." }
    $p0Jars = @((Get-Content -LiteralPath (Join-Path $p0Output 'classpath.txt') -Raw).Trim() -split ';')
    $p0Jupiter = @($p0Jars | Where-Object { $_ -match '[\\/]junit-jupiter-api[\\/]([^\\/]+)[\\/]' })
    if ($p0Jupiter.Count -ne 1 -or $p0Jupiter[0] -notmatch '[\\/]junit-jupiter-api[\\/]([^\\/]+)[\\/]') {
        throw 'Expected one JUnit Jupiter API version.'
    }
    $p0JUnitVersion = $matches[1]
    $p0Jars += Join-Path $LocalRepository "org/junit/platform/junit-platform-launcher/$p0JUnitVersion/junit-platform-launcher-$p0JUnitVersion.jar"
    $p0CopiedJars = @()
    $p0Manifest = @()
    for ($p0Index = 0; $p0Index -lt $p0Jars.Count; $p0Index++) {
        $p0Jar = $p0Jars[$p0Index]
        $p0JarDirectory = Join-Path $p0Output ('deps/' + $p0Index.ToString('D4'))
        New-Item -ItemType Directory -Force -Path $p0JarDirectory | Out-Null
        $p0Copy = Join-Path $p0JarDirectory (Split-Path -Leaf $p0Jar)
        Copy-Item -LiteralPath $p0Jar -Destination $p0Copy -Force
        $p0CopiedJars += $p0Copy
        $p0Manifest += [pscustomobject]@{Original=$p0Jar;Copy=$p0Copy;SHA256=(Get-FileHash -LiteralPath $p0Copy).Hash}
    }
    $p0Manifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $p0Output 'artifact-manifest.json') -Encoding utf8
    $p0Classpath = $p0CopiedJars -join ';'
    $p0CompileRoot = Join-Path $p0Output ('run-' + [Guid]::NewGuid().ToString('N'))
    $p0Classes = Join-Path $p0CompileRoot 'classes'
    $p0Tests = Join-Path $p0CompileRoot 'test-classes'
    $p0Temp = Join-Path $p0Output 'tmp'
    New-Item -ItemType Directory -Force -Path $p0Classes,$p0Tests,$p0Temp | Out-Null
    $p0Sources = @(Get-ChildItem -LiteralPath 'src/main/java' -Filter '*.java' -Recurse)
    if ($Stage -in @('PU','P2','P3','P4')) {
        $p0PlatformSources = @(Get-ChildItem -LiteralPath 'platform-rls/src/main/java','platform-spring-boot-autoconfigure/src/main/java','platform-register/src/main/java' -Filter '*.java' -Recurse)
        $p0PlatformArgs = @('--release','21','-parameters','-encoding','UTF-8','-cp',
            ('"' + $p0Classpath.Replace('\','/') + '"'),'-d',('"' + $p0Classes.Replace('\','/') + '"'))
        $p0PlatformArgs += $p0PlatformSources | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
        $p0PlatformArgsFile = Join-Path $p0Output 'compile-platform.args'
        [IO.File]::WriteAllLines($p0PlatformArgsFile, [string[]]$p0PlatformArgs, [Text.UTF8Encoding]::new($false))
        $ErrorActionPreference = 'Continue' # Windows PowerShell 5 treats native stderr warnings as errors.
        & (Join-Path $JavaHome 'bin/javac.exe') ('@' + $p0PlatformArgsFile) *> (Join-Path $p0Output 'compile-platform.log')
        $ErrorActionPreference = 'Stop'
        if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $p0Output 'compile-platform.log') -Tail 30; throw 'PU platform compilation failed.' }
        $p0Classpath = $p0Classes + ';' + $p0Classpath
        $p0PlatformSources | ForEach-Object { [pscustomobject]@{Path=$_.FullName;SHA256=(Get-FileHash -LiteralPath $_.FullName).Hash} } |
            ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $p0Output 'platform-source-manifest.json') -Encoding utf8
    }
    $p0Arguments = @('--release','21','-parameters','-encoding','UTF-8','-cp',
        ('"' + $p0Classpath.Replace('\','/') + '"'),'-d',('"' + $p0Classes.Replace('\','/') + '"'))
    $p0Arguments += $p0Sources | ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
    $p0SourceManifest = $p0Sources | ForEach-Object {
        [pscustomobject]@{Path=$_.FullName;SHA256=(Get-FileHash -LiteralPath $_.FullName).Hash}
    }
    $p0SourceManifest | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath (Join-Path $p0Output 'source-manifest.json') -Encoding utf8
    $p0ArgumentsFile = Join-Path $p0Output 'compile.args'
    [IO.File]::WriteAllLines($p0ArgumentsFile, [string[]]$p0Arguments, [Text.UTF8Encoding]::new($false))
    $ErrorActionPreference = 'Continue'
    & (Join-Path $JavaHome 'bin/javac.exe') ('@' + $p0ArgumentsFile) *> (Join-Path $p0Output 'compile.log')
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $p0Output 'compile.log') -Tail 25; throw "$Stage application source compilation failed." }
    $p0TestArguments = @('--release','21','-parameters','-encoding','UTF-8','-sourcepath','src/test/java','-cp',
        ('"' + ($p0Classes + ';' + $p0Classpath).Replace('\','/') + '"'),
        '-d',('"' + $p0Tests.Replace('\','/') + '"'),'src/test/java/org/ip/security/PermissionsWorkspaceP0IT.java',
        'scripts/permissions-p0/PermissionsP0Launcher.java','src/test/java/org/ipro/rls/RlsTestAccessConfiguration.java')
    if ($Stage -in @('P1','PU','P2','P3','P4')) {
        $p0TestArguments += Get-ChildItem -LiteralPath 'src/test/java/org/ip/application/access' -Filter '*.java' |
            ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
    }
    if ($Stage -in @('PU','P2','P3','P4')) {
        $p0TestArguments += @('platform-rls/src/test/java/org/ipro/rls/c5/C5OperationPermissionRegistryTest.java',
            'platform-rls/src/test/java/org/ipro/rls/c5/C5PermissionDecisionServiceTest.java',
            'platform-spring-boot-autoconfigure/src/test/java/org/ipro/data/config/C5EntityOperationPermissionRegistrarTest.java',
            'platform-spring-boot-autoconfigure/src/test/java/org/ipro/data/config/C5EntityReadPermissionRegistrarTest.java')
    }
    $puRegressionClasses = @('org.ip.security.C5GrantAdminParityIT','org.ip.security.OperationEffectiveRightsIT',
        'org.ip.security.NamedOperationGrantAdminIT','org.ip.config.InitialGrantIssuanceIT','org.ip.config.InitialGrantIssuanceWiringTest',
        'org.ip.application.register.AccountingObjectPilotIT','org.ipro.register.RlsRecorderSetWriteAuthorizerTest',
        'org.ip.config.PilotFixtureWriteBoundaryIT')
    if ($Stage -eq 'PU' -and $Regression) {
        $p0TestArguments += $puRegressionClasses | ForEach-Object { 'src/test/java/' + $_.Replace('.','/') + '.java' }
    }
    $p0TestArgumentsFile = Join-Path $p0Output 'compile-tests.args'
    [IO.File]::WriteAllLines($p0TestArgumentsFile, [string[]]$p0TestArguments, [Text.UTF8Encoding]::new($false))
    $ErrorActionPreference = 'Continue'
    & (Join-Path $JavaHome 'bin/javac.exe') ('@' + $p0TestArgumentsFile) *> (Join-Path $p0Output 'compile-tests.log')
    $ErrorActionPreference = 'Stop'
    if ($LASTEXITCODE -ne 0) { Get-Content (Join-Path $p0Output 'compile-tests.log') -Tail 25; throw "$Stage test compilation failed." }
    $p0Mockito = @($p0CopiedJars | Where-Object { (Split-Path -Leaf $_) -match '^mockito-core-' })
    if ($p0Mockito.Count -ne 1) { throw 'Expected one Mockito agent.' }
    $p0RunClasspath = @($p0Tests,$p0Classes,'src/test/resources','src/main/resources',
        $(if ($Stage -in @('PU','P2','P3','P4')) { 'platform-register/src/main/resources' }),$p0Classpath) -join ';'
    $p0RunArguments = @('-Dfile.encoding=UTF-8',('-Djava.io.tmpdir=' + $p0Temp),
        ('-Dpermissions.p0.output=' + $p0Output),('-javaagent:' + $p0Mockito[0]),
        '-cp',$p0RunClasspath,'org.ip.probe.PermissionsP0Launcher')
    $p0RunArguments += 'org.ip.security.PermissionsWorkspaceP0IT'
    if ($Stage -in @('P1','PU','P2','P3','P4')) {
        $p0RunArguments += @('org.ip.application.access.PermissionCatalogServiceTest',
            'org.ip.application.access.PermissionContractsTest','org.ip.application.access.PermissionCatalogP1IT')
    }
    if ($Stage -in @('PU','P2','P3','P4')) {
        $p0RunArguments += @('org.ip.application.access.PermissionUnifiedPUIT',
            'org.ipro.rls.c5.C5OperationPermissionRegistryTest','org.ipro.rls.c5.C5PermissionDecisionServiceTest',
            'org.ipro.data.config.C5EntityOperationPermissionRegistrarTest','org.ipro.data.config.C5EntityReadPermissionRegistrarTest')
    }
    if ($Stage -eq 'PU' -and $Regression) { $p0RunArguments += $puRegressionClasses }
    if ($Stage -eq 'P2') { $p0RunArguments += 'org.ip.application.access.PermissionStateP2IT' }
    if ($Stage -eq 'P3') { $p0RunArguments += @('org.ip.application.access.PermissionStateP2IT','org.ip.application.access.PermissionScopeP3IT') }
    if ($Stage -eq 'P4') { $p0RunArguments += @('org.ip.application.access.PermissionStateP2IT','org.ip.application.access.PermissionScopeP3IT','org.ip.application.access.PermissionPatchP4IT') }
    $p0RunArguments += $AdditionalTestClasses
    $ErrorActionPreference = 'Continue'
    & (Join-Path $JavaHome 'bin/java.exe') @p0RunArguments *> (Join-Path $p0Output $TestLogName)
    $ErrorActionPreference = 'Stop'
    $p0Exit = $LASTEXITCODE
    if (Test-Path -LiteralPath (Join-Path $p0Output 'test-summary.txt')) {
        Get-Content -LiteralPath (Join-Path $p0Output 'test-summary.txt')
    }
    if ($p0Exit -ne 0) { throw "$Stage gate failed; inspect test-summary.txt and test-run.log." }
    Write-Output "$requestedStage gate completed. Application target directories and local PostgreSQL were not used for writes."
} finally {
    Pop-Location
    $env:JAVA_HOME = $p0PreviousJavaHome
}
