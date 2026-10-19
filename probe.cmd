@echo off
set ROOT=C:\Users\dimki\projects\forge-1.12.2-meteor
set JAVAC=C:\tools\jdk8u504-b01\bin\javac.exe
set JAVA=C:\tools\jdk8u504-b01\bin\java.exe
cd /d %ROOT%
set CP=libs\mc-1.12.2-mcp.jar;libs\gson-2.10.jar;libs\guava-17.0.jar;libs\log4j-api-2.0-beta9-fixed.jar;libs\log4j-core-2.0-beta9-fixed.jar;libs\lwjgl-2.9.4.jar;libs\launchwrapper-1.12.jar;libs\asm-all-5.0.3.jar
if not exist out mkdir out
%JAVAC% -nowarn -d out -cp "%CP%" src\main\java\com\dimki\meteorb\Probe112.java
