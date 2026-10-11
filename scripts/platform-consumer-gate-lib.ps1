function Assert-ConsumerJUnit {
    param([Parameter(Mandatory)][string]$ReportsDirectory)
    $manifest = Get-Content (Join-Path $PSScriptRoot 'platform-consumer-cases.json') -Raw | ConvertFrom-Json
    $required = @($manifest.requiredCases)
    if ($manifest.schemaVersion -ne 1 -or $required.Count -eq 0 -or
        $required.Count -ne $manifest.expectedTestCount -or
        @($required | Select-Object -Unique).Count -ne $required.Count) { throw 'Invalid consumer case manifest' }
    $reports = @(Get-ChildItem -LiteralPath $ReportsDirectory -Filter 'TEST-*.xml' -File)
    if ($reports.Count -eq 0) { throw 'No consumer JUnit reports' }
    $cases = @()
    foreach ($report in $reports) {
        [xml]$result = Get-Content $report.FullName -Raw
        $suite = $result.testsuite
        foreach ($attribute in @('tests','failures','errors','skipped')) {
            if (-not $suite.HasAttribute($attribute)) { throw "Missing JUnit $attribute in $($report.Name)" }
        }
        if ([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0 -or
            $suite.SelectNodes('testcase/failure|testcase/error|testcase/skipped').Count -ne 0) {
            throw "Failed or skipped consumer tests: $($report.Name)"
        }
        $testCases = @($suite.SelectNodes('testcase'))
        if ([int]$suite.tests -ne $testCases.Count) { throw 'JUnit case count mismatch' }
        $cases += @($testCases | ForEach-Object { $_.GetAttribute('name') })
    }
    if ($cases.Count -ne $required.Count -or @($cases | Select-Object -Unique).Count -ne $cases.Count) {
        throw 'Missing, extra or duplicate consumer cases'
    }
    foreach ($name in $required) { if ($name -notin $cases) { throw "Required consumer case missing: $name" } }
    return $cases
}
