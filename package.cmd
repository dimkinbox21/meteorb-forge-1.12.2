@echo off
set ROOT=C:\Users\dimki\projects\forge-1.12.2-meteor
set JAR=C:\tools\jdk8u504-b01\bin\jar.exe
set JAVA=C:\tools\jdk8u504-b01\bin\java.exe
cd /d %ROOT%
if not exist dist\stage mkdir dist\stage
xcopy /E /I /Y src\main\resources\* dist\stage\ >nul
REM Tools live in their own out dir, so out/ holds only the mod and the jar
REM entry names come out as com/dimki/... the way Forge needs.
if not exist tools\out mkdir tools\out
%JAVAC% -nowarn -d tools\out -cp "libs\asm-all-5.0.3.jar" tools\DeriveMcpJar.java tools\BuildReobfMap.java tools\Reobf.java
%JAVA% -classpath "tools\out;libs\asm-all-5.0.3.jar" Reobf "out" "dist\reobf-classes.jar" "dist\reobf-map.txt" "libs\mc-1.12.2-mcp.jar"
cd dist\stage
%JAR% xf ..\reobf-classes.jar
cd /d %ROOT%
REM The coremod is declared through the jar manifest, the way 1.8's was, so
REM Forge loads the transformer before the mod's own classes.
REM FMLCorePluginContainsFMLMod must stay set: Forge 14 calls it "not
REM recommended", but it is what makes a jar carrying BOTH a coremod and an
REM @Mod be scanned for the mod. Without it the jar is treated as
REM coremod-only - the transformer still loads and works, while @Mod never
REM gets discovered, so no module ticks and no config. Measured: 4 mods
REM loaded instead of 5, with "world was saved with mod meteorb which appears
REM to be missing".
echo Manifest-Version: 1.0> dist\stage\MANIFEST_TMP
echo FMLCorePlugin: com.dimki.meteorb.coremod.CoremodPlugin>> dist\stage\MANIFEST_TMP
echo FMLCorePluginContainsFMLMod: true>> dist\stage\MANIFEST_TMP
%JAR% cfm dist\meteorb-0.1.0.jar dist\stage\MANIFEST_TMP -C dist\stage .


