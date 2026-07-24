@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Axiom\AxiomJDK-21"
set "PATH=%JAVA_HOME%\bin;C:\maven\bin;%PATH%"
cd /d C:\JavaProject\TestVaadin25\Vaa25_1
"C:\maven\bin\mvn.cmd" %*