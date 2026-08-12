@if "%DEBUG%" == "" @echo off
@rem Gradle wrapper script for Windows

setlocal enabledelayedexpansion

set GRADLE_BASE=%USERPROFILE%\.gradle\wrapper\dists\gradle-8.14-bin
for /d %%D in ("%GRADLE_BASE%\*") do set GRADLE_HOME=%%D\gradle-8.14

set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot
set ANDROID_HOME=%USERPROFILE%\AppData\Local\Android\Sdk

if not exist "%GRADLE_HOME%" (
  echo Gradle not found at %GRADLE_HOME%
  exit /b 1
)

"%JAVA_HOME%\bin\java.exe" ^
  -Dorg.gradle.appname=gradlew ^
  -classpath "%GRADLE_HOME%\lib\*" ^
  org.gradle.launcher.GradleMain %*

endlocal
