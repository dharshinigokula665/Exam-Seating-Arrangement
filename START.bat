@echo off
title Exam Seating Arrangement System
if not exist lib mkdir lib
if not exist lib\sqlite-jdbc.jar (
  echo Downloading SQLite database driver (first run only)...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar' -OutFile 'lib\sqlite-jdbc.jar'"
)
if not exist lib\sqlite-jdbc.jar (
  echo Could not download database driver. Check your internet connection.
  pause
  exit /b 1
)
if not exist out mkdir out
javac -cp "lib\sqlite-jdbc.jar" -d out src\Main.java
if errorlevel 1 (
  echo Java compile failed. Check that JDK is installed.
  pause
  exit /b 1
)
java -cp "out;lib\sqlite-jdbc.jar" Main
pause
