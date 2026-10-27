@echo off
setlocal
set "TEST_OUT=%LOCALAPPDATA%\QingzhuMahjongOnline\test-output"
echo Running an isolated four-client TCP test. Please wait...
"%~dp0runtime\bin\java.exe" -Xmx512m -Dfile.encoding=UTF-8 -jar "%~dp0app\qingzhu-mahjong.jar" --network-test "%TEST_OUT%"
if errorlevel 1 (
  echo FAILED. Details:
  if exist "%TEST_OUT%\failure.txt" type "%TEST_OUT%\failure.txt"
  pause
  exit /b 1
)
if not exist "%TEST_OUT%\network-report.json" (
  echo FAILED: no test report was generated.
  pause
  exit /b 1
)
type "%TEST_OUT%\network-results.txt"
echo Reports: %TEST_OUT%
pause
