@echo off
set GRADLE_VERSION=8.13
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
set GRADLE_HOME_DIR=%USERPROFILE%\.gradle\foldbook-distributions\gradle-%GRADLE_VERSION%
if not exist "%GRADLE_HOME_DIR%\bin\gradle.bat" (
  if not exist "%USERPROFILE%\.gradle\foldbook-distributions" mkdir "%USERPROFILE%\.gradle\foldbook-distributions"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $env:TEMP\gradle-%GRADLE_VERSION%.zip; Expand-Archive -Force $env:TEMP\gradle-%GRADLE_VERSION%.zip '%USERPROFILE%\.gradle\foldbook-distributions'"
)
call "%GRADLE_HOME_DIR%\bin\gradle.bat" %*
