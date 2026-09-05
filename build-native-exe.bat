@echo off
REM Builds a genuine native Windows .exe from the jar using jpackage, which
REM ships with any JDK 14+. This avoids the known issue where SCID vs PC
REM (Tcl/Tk based) can't reliably pipe stdin/stdout to a .bat file.
REM
REM Result: native\ChessEngine\ChessEngine.exe -- a real, double-clickable,
REM self-contained executable (it bundles its own Java runtime, so the
REM machine running it doesn't need Java installed separately). Point SCID
REM vs PC directly at that .exe.

cd /d "%~dp0"

if not exist dist\chess-engine.jar (
    echo dist\chess-engine.jar not found -- building it first...
    call build.bat
    if errorlevel 1 goto :error
)

where jpackage >nul 2>nul
if errorlevel 1 (
    echo.
    echo ERROR: "jpackage" was not found on your PATH.
    echo jpackage ships with JDK 14 and later, in the same "bin" folder as javac.
    echo Check your JDK version with:  java -version
    echo If you have an older JDK, install a recent one ^(e.g. Temurin 21^) and try again.
    goto :error
)

echo Using this jpackage:
where jpackage
jpackage --version
echo.

if exist native rmdir /s /q native

echo Running jpackage...
jpackage --type app-image ^
    --input dist ^
    --dest native ^
    --name ChessEngine ^
    --main-jar chess-engine.jar ^
    --main-class engine.UCIEngine ^
    --win-console

if errorlevel 1 goto :error

if not exist native\ChessEngine\ChessEngine.exe (
    echo.
    echo jpackage reported success but ChessEngine.exe was not found where expected.
    echo Contents of the native folder:
    dir /s /b native
    goto :error
)

echo.
echo SUCCESS. Point SCID vs PC at:
echo   %~dp0native\ChessEngine\ChessEngine.exe
echo.
pause
goto :eof

:error
echo.
echo Native build failed -- see the error above.
echo.
pause
exit /b 1
