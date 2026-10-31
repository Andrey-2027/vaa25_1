param([string]$JavaHome='C:/Program Files/Axiom/AxiomJDK-21',[string[]]$TestClasses)
$ErrorActionPreference='Stop'
$root=(Get-Location).Path
$out=Join-Path $root 'outputs/unidr-u4'
if(!(Test-Path -LiteralPath $out)){throw 'Run run-unidr-u4.ps1 to prepare source classes and dependencies first.'}
$dependencies=[IO.File]::ReadAllText((Join-Path $out 'classpath.txt')).Trim()
$classes=Join-Path $out 'v8-4-classes'
[IO.Directory]::CreateDirectory($classes) | Out-Null
$cp="$classes;src/main/resources;src/test/resources;$dependencies;"+(Join-Path $root '.local-maven-repository/org/junit/platform/junit-platform-launcher/6.0.3/junit-platform-launcher-6.0.3.jar')
$javac=Join-Path $JavaHome 'bin/javac.exe'
$sourceArgs=Join-Path $out 'v8-4-source.args'
[IO.File]::WriteAllText($sourceArgs,[IO.File]::ReadAllText((Join-Path $out 'compile.args')).Replace('target/classes',$classes.Replace('\','/')),[Text.UTF8Encoding]::new($false))
$ErrorActionPreference='Continue'
& $javac ('@'+$sourceArgs) 2>&1 | ForEach-Object {"$_"} | Tee-Object -FilePath (Join-Path $out 'v8-4-compile.log')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$testArgs=Join-Path $out 'v8-4-tests.args'
[IO.File]::WriteAllText($testArgs,[IO.File]::ReadAllText((Join-Path $out 'compile-tests.args')).Replace('target/classes',$classes.Replace('\','/')).Replace('target/test-classes',$classes.Replace('\','/')),[Text.UTF8Encoding]::new($false))
& $javac ('@'+$testArgs) 2>&1 | ForEach-Object {"$_"} | Tee-Object -FilePath (Join-Path $out 'v8-4-compile-tests.log')
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$compileArgs=Join-Path $out 'v8-4-compile.args'
[IO.File]::WriteAllLines($compileArgs,[string[]]@('--release','21','-encoding','UTF-8','-cp',('"'+$cp.Replace('\','/')+'"'),'-d',$classes.Replace('\','/'),'scripts/permissions-p0/PermissionsP0Launcher.java'),[Text.UTF8Encoding]::new($false))
& $javac ('@'+$compileArgs)
if($LASTEXITCODE -ne 0){exit $LASTEXITCODE}
$agent=@($dependencies -split ';' | Where-Object {$_ -match '[\\/]mockito-core[\\/].*\.jar$'})
$argsList=@('-Xmx640m','-XX:+UseSerialGC','-XX:ActiveProcessorCount=2','-XX:TieredStopAtLevel=1','-Dfile.encoding=UTF-8',('-Djava.io.tmpdir='+$out+'/tmp'),('-Dpermissions.p0.output='+$out),('-javaagent:'+$agent[0]),'-cp',$cp,'org.ip.probe.PermissionsP0Launcher')
$argsList+=@('org.ip.unidr.model.UniDrDefinitionTest','org.ip.unidr.model.UniDrDefinitionV2Test','org.ip.unidr.model.UniDrDefinitionJsonCodecTest','org.ip.unidr.model.UniDrAppearanceTest','org.ip.unidr.render.UniDrReportCompilerTest','org.ip.unidr.query.UniDrSourceReconcilerTest','org.ip.views.unidr.UniDrEditorTest','org.ip.views.unidr.UniDrSourceChangeUiTest')
if($TestClasses){$argsList=$argsList[0..($argsList.IndexOf('org.ip.probe.PermissionsP0Launcher'))]+$TestClasses}
$argFile=Join-Path $out 'v8-4-run.args'
[IO.File]::WriteAllLines($argFile,[string[]]@($argsList | ForEach-Object {'"'+$_.Replace('\','/')+'"'}),[Text.UTF8Encoding]::new($false))
$ErrorActionPreference='Continue'
& (Join-Path $JavaHome 'bin/java.exe') ('@'+$argFile) 2>&1 | ForEach-Object {"$_"} | Tee-Object -FilePath (Join-Path $out 'v8-4-test.log')
exit $LASTEXITCODE
