@echo off
REM Builds the engine and produces dist\chess-engine.jar (and re-creates the
REM chess-engine.bat wrapper, since the dist folder gets wiped and rebuilt
REM from scratch below).
cd /d "%~dp0"
if exist out rmdir /s /q out
if exist dist rmdir /s /q dist
mkdir out
mkdir dist
javac --release 8 -d out src\engine\*.java
if errorlevel 1 goto :error
echo Main-Class: engine.UCIEngine> dist\MANIFEST.MF
jar cfm dist\chess-engine.jar dist\MANIFEST.MF -C out .
if errorlevel 1 goto :error
del dist\MANIFEST.MF

REM Re-create the wrapper that SCID vs PC (and similar GUIs) actually point at.
(
echo @echo off
echo java -jar "%%~dp0chess-engine.jar" %%*
) > dist\chess-engine.bat

echo Built dist\chess-engine.jar
echo Built dist\chess-engine.bat
echo Test it with:  java -jar dist\chess-engine.jar
goto :eof

:error
echo Build failed.
exit /b 1
