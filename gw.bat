@echo off
rem Dev helper: runs the Gradle wrapper with Android Studio's bundled JDK (useful from WSL: cmd.exe /c gw.bat <tasks>)
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "%~dp0"
call gradlew.bat %*
