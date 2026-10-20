@echo off
setlocal enabledelayedexpansion

echo ========================================================
echo   Smart Attendance Marking System - Build Script
echo ========================================================

set PROJECT_DIR=%~dp0
cd /d "%PROJECT_DIR%"

echo [1/4] Preparing directories...
if not exist "src\main\webapp\WEB-INF\classes" mkdir "src\main\webapp\WEB-INF\classes"
if not exist "src\main\webapp\WEB-INF\lib" mkdir "src\main\webapp\WEB-INF\lib"
if not exist "dist" mkdir "dist"

echo [2/4] Syncing libraries...
copy /Y "lib\*.jar" "src\main\webapp\WEB-INF\lib\" >nul 2>&1

echo [3/4] Compiling Java source files...
dir /s /B src\main\java\*.java > sources.txt
javac -encoding UTF-8 -cp "lib\*;tomcat\lib\*" -d "src\main\webapp\WEB-INF\classes" @sources.txt
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Java compilation failed!
    del sources.txt >nul 2>&1
    pause
    exit /b %ERRORLEVEL%
)
del sources.txt >nul 2>&1
echo [OK] Compilation successful.

echo [4/4] Packaging WAR file...
cd /d "%PROJECT_DIR%src\main\webapp"
jar -cvf "%PROJECT_DIR%dist\attendance.war" * >nul 2>&1
cd /d "%PROJECT_DIR%"

echo [OK] Package created: dist\attendance.war

if exist "tomcat\webapps" (
    echo [*] Deploying to Tomcat...
    copy /Y "dist\attendance.war" "tomcat\webapps\attendance.war" >nul 2>&1
    echo [OK] Deployed to tomcat\webapps\attendance.war
)

echo.
echo Build completed successfully!
echo To run the application, execute: run.bat
echo ========================================================
