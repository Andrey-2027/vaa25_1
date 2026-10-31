param([switch]$Prepare,[switch]$ApplyAndRun,[switch]$RuntimeOnly,[string[]]$CaseNames,[string]$Repository='C:/Users/strim/.m2/repository',[string]$CRun='r2',[string]$Settings)
$ErrorActionPreference='Stop'
$repo=(Get-Location).Path
$work=Join-Path $repo 'outputs/entity-generation-g1-extension/e4-a/work'
$inputs=Join-Path $repo 'outputs/entity-generation-g1-extension/e4-a/e4a-r2/input'
$cases=@('catalog-0','catalog-1','catalog-2','document-0','document-1','document-2','owned-catalog-2')
if($CaseNames){$cases=$CaseNames}
$maven='C:/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.3/plugins/maven/lib/maven3/bin/mvn.cmd'
$env:JAVA_HOME='C:/Program Files/Axiom/AxiomJDK-21'
$local=$Repository
function WriteJson($path,$value){[IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 70),[Text.UTF8Encoding]::new($false))}
function Locations($node){
    if($node.groupId -eq 'org.ipro' -and $node.type -eq 'jar'){
        $classifier=if($node.classifier){[string]$node.classifier}else{''}
        $suffix=if($classifier){'-'+$classifier}else{''}
        [pscustomobject]@{id="$($node.groupId):$($node.artifactId):$($node.type):${classifier}:$($node.version)";path="$local/org/ipro/$($node.artifactId)/$($node.version)/$($node.artifactId)-$($node.version)$suffix.jar"}
    }
    foreach($child in $node.children){Locations $child}
}
foreach($case in $cases){
    $project=Join-Path $work "applied/$case"
    $hasRows=$case -notlike '*-0'
    $argsMaven=@('-o','-B',"-Dmaven.repo.local=$local",'-f',"$project/pom.xml")
    if($Settings){$argsMaven=@('-B','-s',$Settings,"-Dmaven.repo.local=$local",'-f',"$project/pom.xml")}
    if($hasRows){$argsMaven+='-Pg1-sections'}
    if($Prepare){
        if(Test-Path "$project/.g1-apply-v2"){throw "Already applied: $case"}
        if(!(Test-Path "$project/pom.xml")){
            New-Item -ItemType Directory $project -Force|Out-Null
            Copy-Item scripts/entity-generation-g1/consumer/* $project -Recurse
        }
        Copy-Item "scripts/entity-generation-g1/scenarios/e4a-$case/ScenarioSpec.java" "$project/src/test/java/com/example/generated/harness/ScenarioSpec.java" -Force
        if($hasRows){Copy-Item scripts/entity-generation-g1/section-tests/SectionsScenarioTest.java "$project/src/test/java/com/example/generated/harness/"}
        & $maven @argsMaven generate-test-resources *> "$work/dependencies-$case.log"
        if($LASTEXITCODE){throw "Dependency resolution failed: $case"}
        $tree=Get-Content "$project/target/consumer-runtime-dependency-tree.json" -Raw|ConvertFrom-Json
        $deps=@(Locations $tree|Sort-Object id -Unique)
        $expected=if($hasRows){18}else{17}
        if($deps.Count -ne $expected){throw "Dependencies count: $case = $($deps.Count)"}
        WriteJson "$work/$case-dependencies.json" $deps
        Write-Output "Prepared: $case"
    }
    if($ApplyAndRun){
        $cp=(Get-Content "$work/d-classpath.txt" -Raw).Trim()
        $summary="$work/c/$case-$CRun/summary.json"
        foreach($stage in $(if($RuntimeOnly){@()}else{@('applied','noop')})){
            $result="$work/$case-$stage.json"
            & "$env:JAVA_HOME/bin/java.exe" -cp $cp org.ipro.design.apply.ApplyHarness --apply $project src/main/java $summary "$inputs/$case/reviewed-plan.json" "$work/$case-dependencies.json" $result
            $value=Get-Content $result -Raw|ConvertFrom-Json
            if($value.status -ne $stage.ToUpperInvariant()){throw "D $case $stage : $($value.status) $($value.code)"}
        }
        $suite=if($hasRows){'SectionsScenarioTest'}else{'GenerationScenarioTest'}
        & $maven @argsMaven "-Dtest=$suite" clean test *> "$work/runtime-$case.log"
        if($LASTEXITCODE){throw "Applied runtime failed: $case"}
        Write-Output "Applied, NOOP and runtime passed: $case"
    }
}
