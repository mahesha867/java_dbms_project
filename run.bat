@echo off
setlocal

echo ========================================================
echo   Starting Smart Attendance Marking System...
echo ========================================================

set PROJECT_DIR=%~dp0
cd /d "%PROJECT_DIR%"

if not exist "dist\attendance.war" (
    echo Building project first...
    call build.bat
)

if not exist "tomcat\bin\catalina.bat" (
    echo [ERROR] Tomcat not found in tomcat directory!
    pause
    exit /b 1
)

echo [*] Starting Tomcat server on http://localhost:8080/attendance
echo [*] Press Ctrl+C in the Tomcat window to stop.
echo.

set CATALINA_HOME=%PROJECT_DIR%tomcat
start "Tomcat - Smart Attendance Server" "%PROJECT_DIR%tomcat\bin\catalina.bat" run

timeout /t 4 /nobreak >nul
start http://localhost:8080/attendance/login

echo Server launched! Browser opened to http://localhost:8080/attendance/login
