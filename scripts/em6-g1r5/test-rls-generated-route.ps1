#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('none','day')][string]$Mode,
    [Parameter(Mandatory)][string]$Inputs,
    [Parameter(Mandatory)][string]$CSummary,
    [Parameter(Mandatory)][string]$CoreJar,
    [Parameter(Mandatory)][string]$StandRoot,
    [Parameter(Mandatory)][string]$OutputRoot,
    [Parameter(Mandatory)][string]$LocalRepository,
    [ValidateSet('fixture','browser')][string]$EvidenceOrigin='fixture',
    [switch]$LegacyNone,
    [string]$JavaHome='C:/Program Files/Axiom/AxiomJDK-21',
    [string]$Maven='C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd'
)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$repo=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$hostExe=(Get-Process -Id $PID).Path
$StandRoot=[IO.Path]::GetFullPath($StandRoot)
$OutputRoot=[IO.Path]::GetFullPath($OutputRoot)
if($StandRoot.StartsWith($repo,[StringComparison]::OrdinalIgnoreCase)){throw 'Consumer copies must be outside the checkout'}
New-Item -ItemType Directory $StandRoot,$OutputRoot -Force | Out-Null
if($LegacyNone -and $Mode -ne 'none'){throw 'LegacyNone supports NONE only'}
$scenario=if($LegacyNone){'register-none'}else{"register-$Mode-rls"}
$spec=Join-Path $repo "scripts/entity-generation-g1/scenarios/$scenario/ScenarioSpec.java"
$planFile=Join-Path $Inputs 'plan.json'
$plan=Get-Content $planFile -Raw | ConvertFrom-Json
$c=Get-Content $CSummary -Raw | ConvertFrom-Json
$expectedGenerator=if($LegacyNone){'register-java-v1'}else{'register-java-v2'}
if($c.status -ne 'success' -or $c.plan.planId -ne $plan.planId -or $plan.generatorVersion -ne $expectedGenerator){throw 'Matching successful C and plan required'}
if((Get-FileHash $spec -Algorithm SHA256).Hash.ToLowerInvariant() -ne $c.scenario.specSha256){throw 'Independent spec differs from C'}
if($EvidenceOrigin -eq 'browser') {
    $browser=Get-Content (Join-Path $Inputs 'browser-verification.json') -Raw|ConvertFrom-Json
    if(!$browser.matches -or $browser.planId -ne $plan.planId -or $browser.sha256 -ne $plan.files[0].sha256) {throw 'Matching browser verification required'}
}
$oldJava=$env:JAVA_HOME
$env:JAVA_HOME=$JavaHome
$summary=[ordered]@{mode=$Mode;scenario=$scenario;generator=$expectedGenerator;status='RUNNING';scope="$EvidenceOrigin Java -> C/D/applied backend; forms/restart acceptance separate";planId=$plan.planId;coreJarSha256=(Get-FileHash $CoreJar -Algorithm SHA256).Hash.ToLowerInvariant();steps=[ordered]@{}}
function Json($path,$value){[IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 25)+"`n",[Text.UTF8Encoding]::new($false))}
function Fresh-Project($name){
    $project=Join-Path $StandRoot $name
    if(Test-Path $project){throw "Fresh project already exists: $project"}
    New-Item -ItemType Directory $project | Out-Null
    Copy-Item "$repo/scripts/entity-generation-g1/register-consumer/*" $project -Recurse
    Copy-Item $spec "$project/src/test/java/com/example/generated/harness/ScenarioSpec.java"
    if(!$LegacyNone){Copy-Item "$repo/scripts/entity-generation-g1/register-rls-tests/RegisterRlsSecurityTest.java" "$project/src/test/java/com/example/generated/harness/"}
    foreach($support in $c.supportSources){
        if((Get-FileHash (Join-Path $project $support.relativePath) -Algorithm SHA256).Hash.ToLowerInvariant() -ne $support.sha256){throw "Support source differs from C: $($support.relativePath)"}
    }
    if(Test-Path (Join-Path $project ('src/main/java/'+$plan.files[0].relativePath))){throw 'Old target source exists'}
    if(@(Get-ChildItem $project -Recurse -Filter '*.class' -File).Count){throw 'Old class files exist'}
    return $project
}
function Maven-Run($project,$log,[string[]]$goals){
    & $Maven -B -ntp -o "-Dmaven.repo.local=$LocalRepository" -f "$project/pom.xml" @goals *> $log
    if($LASTEXITCODE){throw "Maven failed: $log"}
}
function Apply-Run($name,$project,$tree,$expected,$inputSummary=$CSummary){
    & $hostExe -NoProfile -File "$repo/scripts/apply-entity-generation-g1.ps1" -Apply -SkipCoreBuild -CoreClasses $CoreJar -ProjectRoot $project -CSummary $inputSummary -ReviewedPlan $planFile -DependencyTree $tree -LocalRepository $LocalRepository -JavaHome $JavaHome -Maven $Maven -OutputDirectory $OutputRoot -RunId $name *> "$OutputRoot/$name.log"
    $code=$LASTEXITCODE
    $result=Get-Content "$OutputRoot/$name/apply-result.json" -Raw | ConvertFrom-Json
    if($result.status -ne $expected -or $code -ne $result.exitCode){throw "Unexpected $name result: $($result.status)/$($result.code), exit $code"}
    $summary.steps[$name]=@{status=$result.status;code=$result.code;exitCode=$code;result="$name/apply-result.json"}
    Json "$OutputRoot/summary.json" $summary
    return $result
}
try{
    $project=Fresh-Project "$Mode-applied"
    $summary['project']=$project
    $tree="$project/backend-dependencies.json"
    Maven-Run $project "$OutputRoot/dependencies.log" @('dependency:tree','-Dscope=runtime','-DoutputType=json',"-DoutputFile=$tree")
    $applied=Apply-Run "$Mode-apply" $project $tree 'APPLIED'
    $target=Join-Path $project ('src/main/java/'+$plan.files[0].relativePath)
    if((Get-FileHash $target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $plan.files[0].sha256){throw 'Applied source differs from plan'}
    $receiptHash=(Get-FileHash $applied.receipt -Algorithm SHA256).Hash
    $requiredTests=if($LegacyNone){'RegisterScenarioTest'}else{'RegisterScenarioTest,RegisterRlsSecurityTest'}
    Maven-Run $project "$OutputRoot/applied-runtime.log" @('test',"-Dtest=$requiredTests")
    $suites=@(Get-ChildItem "$project/target/surefire-reports" -Filter 'TEST-*.xml' -File | ForEach-Object {
        [xml]$xml=Get-Content $_.FullName -Raw;$s=$xml.testsuite
        @{name=[string]$s.name;tests=[int]$s.tests;errors=[int]$s.errors;failures=[int]$s.failures;skipped=[int]$s.skipped}
    })
    $requiredCount=if($LegacyNone){1}else{2}
    if($suites.Count -ne $requiredCount -or @($suites|Where-Object {$_.tests -eq 0 -or $_.errors -or $_.failures -or $_.skipped}).Count){throw 'All mandatory applied-runtime suites must pass'}
    $summary['runtime']=$suites
    Copy-Item "$project/target/surefire-reports" "$OutputRoot/applied-runtime-reports" -Recurse
    $noop=Apply-Run "$Mode-noop" $project $tree 'NOOP'
    if((Get-FileHash $noop.receipt -Algorithm SHA256).Hash -ne $receiptHash){throw 'NOOP changed the receipt'}
    $conflictProject=Fresh-Project "$Mode-conflict"
    [void](Apply-Run "$Mode-conflict-setup" $conflictProject $tree 'APPLIED')
    $conflictTarget=Join-Path $conflictProject ('src/main/java/'+$plan.files[0].relativePath)
    [IO.File]::AppendAllText($conflictTarget,"// controlled manual edit for conflict verification`n",[Text.UTF8Encoding]::new($false))
    $edited=(Get-FileHash $conflictTarget -Algorithm SHA256).Hash
    $conflict=Apply-Run "$Mode-conflict" $conflictProject $tree 'CONFLICT'
    if($conflict.code -ne 'CONFLICT_MODIFIED' -or (Get-FileHash $conflictTarget -Algorithm SHA256).Hash -ne $edited){throw 'Manual edit was not preserved'}
    Copy-Item $conflictTarget "$OutputRoot/conflicting-source.java.txt"
    $rollbackProject=Fresh-Project "$Mode-rollback"
    $jackson=@('databind','core','annotations')|ForEach-Object {Join-Path $LocalRepository "com/fasterxml/jackson/core/jackson-$_/2.18.4/jackson-$_-2.18.4.jar"}
    $cp=(@("$OutputRoot/$Mode-apply/classes",$CoreJar)+$jackson)-join ';'
    & "$JavaHome/bin/java.exe" -cp $cp org.ipro.design.apply.RegisterRollbackCheck $rollbackProject $CSummary $planFile "$OutputRoot/$Mode-apply/current-dependencies.json" "$OutputRoot/rollback-result.json" *> "$OutputRoot/rollback.log"
    if($LASTEXITCODE){throw 'Actual-input rollback probe failed'}
    if(!$LegacyNone){
    $missingProject=Fresh-Project "$Mode-missing-security"
    $missing=Get-Content $CSummary -Raw|ConvertFrom-Json
    $removed=@($missing.tests.suites|Where-Object {$_.name -eq 'com.example.generated.harness.RegisterRlsSecurityTest'})
    $missing.tests.suites=@($missing.tests.suites|Where-Object {$_.name -ne 'com.example.generated.harness.RegisterRlsSecurityTest'})
    $missing.tests.run=[int]($missing.tests.run-($removed.run|Measure-Object -Sum).Sum);$missing.tests.reports=$missing.tests.suites.Count
    Json "$OutputRoot/c-without-rls-suite.json" $missing
    $rejected=Apply-Run "$Mode-missing-security" $missingProject $tree 'REJECTED' "$OutputRoot/c-without-rls-suite.json"
    if($rejected.code -ne 'C_TESTS_INCOMPLETE' -or (Test-Path (Join-Path $missingProject ('src/main/java/'+$plan.files[0].relativePath)))){throw 'D accepted C without the mandatory RLS suite'}
    }
    $summary['rollback']='PASS';$summary['noOldTargetOrClasses']=$true;$summary.status='PASS'
}catch{$summary.status='FAIL';$summary['error']=$_.Exception.Message;throw}
finally{Json "$OutputRoot/summary.json" $summary;$env:JAVA_HOME=$oldJava}
Write-Output "Register $scenario D/applied-runtime: PASS"
