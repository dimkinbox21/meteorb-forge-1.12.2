@echo off
set ROOT=C:\Users\dimki\projects\forge-1.12.2-meteor
set JAVAC=C:\tools\jdk8u504-b01\bin\javac.exe
set JAVA=C:\tools\jdk8u504-b01\bin\java.exe
cd /d %ROOT%
set CP=libs\mc-1.12.2-mcp.jar;libs\gson-2.10.jar;libs\guava-17.0.jar;libs\log4j-api-2.0-beta9-fixed.jar;libs\log4j-core-2.0-beta9-fixed.jar;libs\lwjgl-2.9.4+legacyfabric.17.jar;libs\launchwrapper-1.12.jar;libs\asm-all-5.0.3.jar;libs\forge-1.12.2-14.23.5.2859-universal.jar
if not exist out mkdir out
if not exist tools\out mkdir tools\out
%JAVAC% -nowarn -d tools\out -cp "libs\asm-all-5.0.3.jar" tools\DeriveMcpJar.java tools\BuildReobfMap.java tools\Reobf.java
if not exist dist\reobf-map.txt %JAVA% -classpath "tools\out;libs\asm-all-5.0.3.jar" BuildReobfMap "tools\joined.srg" "tools\fields.csv" "tools\methods.csv" "dist\reobf-map.txt"
%JAVAC% -nowarn -d out -cp "%CP%" src\main\java\com\dimki\meteorb\*.java src\main\java\com\dimki\meteorb\coremod\*.java
if errorlevel 1 exit /b 1
REM Reobfuscation and packaging are package.cmd.
