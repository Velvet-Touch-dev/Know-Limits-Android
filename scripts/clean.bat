@echo off
REM Navigate to project root directory
cd %~dp0\..

REM Stop any running Gradle daemon
call gradlew --stop

REM Clean the project
call gradlew clean

REM Clear Gradle caches (Windows version)
rmdir /s /q %USERPROFILE%\.gradle\caches

REM Create the .gradle directory in the project if it doesn't exist
if not exist .gradle mkdir .gradle

REM Create a touch file to indicate cleaning was done
type nul > .gradle\.cleaned

echo Clean completed. Please rebuild your project.
pause
