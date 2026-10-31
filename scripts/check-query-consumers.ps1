[CmdletBinding()]
param(
    # Maven executable; default is the IntelliJ-bundled Maven used by this workspace.
    [string]$MavenCommand = '',
    # Skip installing platform-query/platform-query-vaadin (use only for a quick re-run).
    [switch]$SkipInstall
)
# M1 step 5: independent negative consumers for the query artifacts.
#
# backend: platform-query + platform-report-core, no Vaadin/Jasper.
# ui:      platform-query-vaadin + Vaadin, no Jasper/DynamicReports/reportui.
#
# For each consumer the script proves both directions:
#   positive: it compiles against the installed artifacts and the classpath has
#             no report engine libraries;
#   negative: with the query profile disabled the same sources must fail to
#             compile. A stale copy in the application target directory cannot
#             satisfy the consumer: it is a separate Maven project.
$ErrorActionPreference = 'Stop'

$scriptDirectory = Split-Path -Parent $PSCommandPath
$projectDirectory = Split-Path -Parent $scriptDirectory
$consumerRoot = Join-Path $projectDirectory 'platform-build/query-consumer'

function Resolve-MavenCommand {
    param([string]$Requested)
    if ($Requested) {
        $resolved = [System.IO.Path]::GetFullPath($Requested)
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw "Maven executable not found: $resolved"
        }
        return $resolved
    }
    if ($env:IDEA_MAVEN_HOME) {
        $fromEnvironment = Join-Path $env:IDEA_MAVEN_HOME 'bin\mvn.cmd'
        if (Test-Path -LiteralPath $fromEnvironment -PathType Leaf) {
            return [System.IO.Path]::GetFullPath($fromEnvironment)
        }
    }
    $mavenOnPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($mavenOnPath) {
        return $mavenOnPath.Source
    }
    $jetBrains = Join-Path $env:ProgramFiles 'JetBrains'
    if (Test-Path -LiteralPath $jetBrains -PathType Container) {
        $candidate = Get-ChildItem -LiteralPath $jetBrains -Directory -Filter 'IntelliJ IDEA*' |
            ForEach-Object {
                Get-Item -LiteralPath (Join-Path $_.FullName 'plugins\maven\lib\maven3\bin\mvn.cmd') `
                    -ErrorAction SilentlyContinue
            } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($candidate) {
            return $candidate.FullName
        }
    }
    throw 'Maven was not found. Pass -MavenCommand or set IDEA_MAVEN_HOME.'
}

function Invoke-Maven {
    param(
        [string[]]$Arguments,
        [string]$Label,
        [switch]$ExpectFailure
    )
    $log = Join-Path ([System.IO.Path]::GetTempPath()) ("query-consumer-" + [guid]::NewGuid() + ".log")
    try {
        & $maven @Arguments *> $log
        $exitCode = $LASTEXITCODE
        if ($ExpectFailure) {
            if ($exitCode -eq 0) {
                $tail = Get-Content -LiteralPath $log -Tail 40 | Out-String
                throw "$Label unexpectedly succeeded (exit 0).`n$tail"
            }
            Write-Host ("OK negative: {0} (exit {1})" -f $Label, $exitCode)
            return $exitCode
        }
        if ($exitCode -ne 0) {
            $tail = Get-Content -LiteralPath $log -Tail 60 | Out-String
            throw "$Label failed (exit $exitCode).`n$tail"
        }
        Write-Host ("OK positive: {0}" -f $Label)
        return $exitCode
    } finally {
        Remove-Item -LiteralPath $log -Force -ErrorAction SilentlyContinue
    }
}

function Get-ArtifactClasspath {
    param([string]$Pom, [string]$Label)
    $classpathFile = Join-Path (Split-Path -Parent $Pom) 'target/consumer-classpath.txt'
    Invoke-Maven -Arguments @(
        '-B', '-q', '-f', $Pom,
        'dependency:build-classpath',
        "-Dmdep.outputFile=$classpathFile"
    ) -Label "$Label dependency classpath"
    if (-not (Test-Path -LiteralPath $classpathFile)) {
        throw "Classpath file was not written: $classpathFile"
    }
    $entries = (Get-Content -LiteralPath $classpathFile -Raw).Trim() -split [System.IO.Path]::PathSeparator
    return @($entries | Where-Object { $_ } | ForEach-Object { [System.IO.Path]::GetFileName($_) })
}

function Assert-NoForbiddenArtifacts {
    param([string[]]$ArtifactNames, [string[]]$Forbidden, [string]$Label)
    $offenders = @($ArtifactNames | Where-Object {
        $name = $_
        @($Forbidden | Where-Object { $name -match $_ }).Count -gt 0
    })
    if ($offenders.Count -gt 0) {
        throw "$Label contains forbidden artifacts: $($offenders -join ', ')"
    }
    Write-Host ("OK classpath: {0} has no {1}" -f $Label, ($Forbidden -join '/'))
}

$maven = Resolve-MavenCommand $MavenCommand

# Maven follows JAVA_HOME even when `java` on PATH is newer (this workspace has
# JAVA_HOME pointing at a JDK 17). The modules require release 21, so select the
# workspace JDK explicitly instead of letting the compiler fail on `release 21`.
# The workspace canonical toolchain is JDK 21 (scripts/local-dependencies.json).
# JAVA_HOME may point at an older JDK on this machine, while `java` on PATH is 21;
# Maven follows JAVA_HOME, so pin the Axiom JDK 21 explicitly.
$jdk21 = 'C:\Program Files\Axiom\AxiomJDK-21'
if (Test-Path -LiteralPath (Join-Path $jdk21 'bin/java.exe')) {
    $env:JAVA_HOME = $jdk21
} elseif (-not $env:JAVA_HOME) {
    throw 'JDK 21 was not found. Pass a JDK 21 via JAVA_HOME.'
}
Write-Host "JDK: $env:JAVA_HOME"

if (-not $SkipInstall) {
    Invoke-Maven -Arguments @('-B', '-q', '-f', (Join-Path $projectDirectory 'platform-query/pom.xml'),
        '-DskipTests', 'clean', 'install') -Label 'platform-query install'
    Invoke-Maven -Arguments @('-B', '-q', '-f', (Join-Path $projectDirectory 'platform-query-vaadin/pom.xml'),
        '-DskipTests', 'clean', 'install') -Label 'platform-query-vaadin install'
}

$backendPom = Join-Path $consumerRoot 'backend/pom.xml'
$uiPom = Join-Path $consumerRoot 'ui/pom.xml'

Invoke-Maven -Arguments @('-B', '-q', '-f', $backendPom, 'clean', 'compile') -Label 'backend consumer compile'
$backendArtifacts = Get-ArtifactClasspath $backendPom 'backend consumer'
Assert-NoForbiddenArtifacts -ArtifactNames $backendArtifacts `
    -Forbidden @('(?i)jasperreports', '(?i)dynamicreports', '(?i)reportui',
                 '(?i)^platform-jr', '(?i)^platform-report(?!-core)', '(?i)^vaadin') `
    -Label 'backend consumer'
if (-not ($backendArtifacts | Where-Object { $_ -match '(?i)^platform-query-' })) {
    throw 'backend consumer classpath does not contain platform-query'
}

Invoke-Maven -Arguments @('-B', '-q', '-f', $uiPom, 'clean', 'compile') -Label 'UI consumer compile'
$uiArtifacts = Get-ArtifactClasspath $uiPom 'UI consumer'
Assert-NoForbiddenArtifacts -ArtifactNames $uiArtifacts `
    -Forbidden @('(?i)jasperreports', '(?i)dynamicreports', '(?i)reportui',
                 '(?i)^platform-jr', '(?i)^platform-report(?!-core)') `
    -Label 'UI consumer'
if (-not ($uiArtifacts | Where-Object { $_ -match '(?i)^platform-query-vaadin' })) {
    throw 'UI consumer classpath does not contain platform-query-vaadin'
}

Invoke-Maven -Arguments @('-B', '-q', '-f', $backendPom, '-P!with-query', 'clean', 'compile') `
    -Label 'backend consumer without platform-query' -ExpectFailure
Invoke-Maven -Arguments @('-B', '-q', '-f', $uiPom, '-P!with-query-ui', 'clean', 'compile') `
    -Label 'UI consumer without platform-query-vaadin' -ExpectFailure

Write-Host 'M1 step 5 negative consumers: PASS (both directions)'
