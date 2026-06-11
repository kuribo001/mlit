@echo off
setlocal

set "APP_HOME=%~dp0"
set "GRADLE_VERSION=9.5.1"
set "DIST_DIR=%APP_HOME%.gradle-dist"
set "GRADLE_DIR=%DIST_DIR%\gradle-%GRADLE_VERSION%"
set "ZIP_FILE=%DIST_DIR%\gradle-%GRADLE_VERSION%-bin.zip"
set "DIST_URL=https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"

if exist "%GRADLE_DIR%\bin\gradle.bat" goto run_gradle

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

if not exist "%ZIP_FILE%" (
  echo Downloading Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri '%DIST_URL%' -OutFile '%ZIP_FILE%'"
  if errorlevel 1 exit /b 1
)

echo Extracting Gradle %GRADLE_VERSION%...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP_FILE%' -DestinationPath '%DIST_DIR%' -Force"
if errorlevel 1 exit /b 1

:run_gradle
call "%GRADLE_DIR%\bin\gradle.bat" %*
exit /b %errorlevel%
