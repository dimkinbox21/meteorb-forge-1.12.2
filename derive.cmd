@echo off
set ROOT=C:\Users\dimki\projects\forge-1.12.2-meteor
set JAVAC=C:\tools\jdk8u504-b01\bin\javac.exe
set JAVA=C:\tools\jdk8u504-b01\bin\java.exe
cd /d %ROOT%
if not exist out mkdir out
%JAVAC% -nowarn -d out -cp "libs\asm-all-5.0.3.jar" tools\DeriveMcpJar.java tools\BuildReobfMap.java tools\Reobf.java || exit /b 1
%JAVA% -classpath "out;libs\asm-all-5.0.3.jar" DeriveMcpJar "libs\minecraft-1.12.2-client.jar" "tools\joined.srg" "tools\fields.csv" "tools\methods.csv" "libs\mc-1.12.2-mcp.jar"
%JAVA% -classpath "out;libs\asm-all-5.0.3.jar" BuildReobfMap "tools\joined.srg" "tools\fields.csv" "tools\methods.csv" "dist\reobf-map.txt"
%JAVA% -classpath "out;libs\asm-all-5.0.3.jar" Reobf "out" "dist\reobf-classes.jar" "dist\reobf-map.txt" "libs\mc-1.12.2-mcp.jar"
