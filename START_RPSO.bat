@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "RPSO_EDITOR=btsn.workflowEditor\dist\ProcessEditor.jar"
if not exist "%RPSO_EDITOR%" (
    echo ERROR: %RPSO_EDITOR% was not found.
    echo.
    echo This launcher is intended for a compiled RPSO distribution.
    echo To build from the sources, run:
    echo     ant -f release\build.xml dist
    goto :failed
)

where java >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java was not found on PATH. Install a JDK, version 15 or later.
    goto :failed
)
where javac >nul 2>&1
if errorlevel 1 (
    echo ERROR: A Java Development Kit is required for RPSO workflow builds.
    echo Install JDK 17 or 21 and ensure javac is available on PATH.
    goto :failed
)

if exist "%~dp0tools\ant\lib\ant-launcher.jar" if exist "%~dp0tools\ant\lib\ant.jar" (
    set "ANT_HOME=%~dp0tools\ant"
    goto :ant_ready
)
if defined ANT_HOME if exist "%ANT_HOME%\lib\ant-launcher.jar" goto :ant_ready
where ant >nul 2>&1
if errorlevel 1 (
    echo WARNING: Apache Ant was not found on PATH or in ANT_HOME.
    echo You can browse the editor, but Build and Run on hosts require Ant 1.10.2+.
    echo This checkout has no bundled Ant. Install Ant, or configure its folder
    echo in the editor's Run window. The release ZIP includes its own Ant.
    echo.
)

:ant_ready
echo Starting RPSO Process Editor...
rem Give each extracted release its own settings. Fresh installs default to SingleHost.
rem '.' resolves to the directory selected by cd above, including paths with spaces.
java -Drpso.distribution.root=. -jar "%RPSO_EDITOR%"
if errorlevel 1 (
    echo.
    echo ERROR: The Process Editor exited with an error.
    goto :failed
)
exit /b 0

:failed
echo.
pause
exit /b 1
