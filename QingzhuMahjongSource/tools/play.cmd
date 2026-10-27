@echo off
setlocal
if not exist "%~dp0runtime\bin\javaw.exe" (
  echo Missing bundled runtime. Extract the complete ZIP before starting.
  pause
  exit /b 1
)
start "" "%~dp0runtime\bin\javaw.exe" -Xmx512m -Dfile.encoding=UTF-8 -jar "%~dp0app\qingzhu-mahjong.jar"
