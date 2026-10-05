@echo off
cd /d "%~dp0"
if "%~1"=="" (
    echo Usage: launch.bat p1^|p2^|p3^|p4^|p5^|p6^|monitor [v001]
    exit /b 1
)
start "BTSN infrastructure %~1" java -jar "%~dp0btsn-infrastructure.jar" %*
if /i not "%~1"=="monitor" start "BTSN business %~1" java -jar "%~dp0btsn-business-services.jar" %*
