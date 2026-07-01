@echo off
setlocal
REM ============================================================
REM  ChatHub - connect two Android emulators on one PC
REM ------------------------------------------------------------
REM  Why: emulators sit behind their own NAT and cannot reach
REM  each other by IP. This script forwards the host PC port to
REM  the "server" emulator so the other emulator can reach it
REM  via the special address 10.0.2.2.
REM
REM  Usage:  scripts\emu_connect.bat <HOST_SERIAL> [PORT]
REM  e.g.    scripts\emu_connect.bat emulator-5554 8888
REM    HOST_SERIAL = the emulator that will host (see: adb devices)
REM    PORT        = default 8888
REM
REM  It (1) installs the debug APK to ALL connected devices and
REM     (2) sets "adb forward tcp:<PORT>" on the HOST emulator.
REM  Then: HOST picks "Create room" on that port; the other
REM  emulator picks "Join", IP = 10.0.2.2, same port.
REM  (Full Chinese guide: Docs\TEACHING.md section 3)
REM ============================================================

REM Locate adb: prefer default SDK path, fall back to PATH.
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"

set "HOST=%~1"
set "PORT=%~2"
if "%PORT%"=="" set "PORT=8888"

if "%HOST%"=="" (
  echo [!] Please pass the HOST emulator serial. Connected devices:
  "%ADB%" devices
  echo.
  echo Usage: scripts\emu_connect.bat emulator-5554 8888
  exit /b 1
)

REM APK path (this script lives in scripts\, project root is one up).
set "APK=%~dp0..\app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
  echo [!] APK not found: %APK%
  echo     Build first: gradlew.bat :app:assembleDebug
  exit /b 1
)

echo [*] Installing APK to all connected emulators/devices...
for /f "skip=1 tokens=1" %%d in ('"%ADB%" devices') do (
  if not "%%d"=="" (
    echo     -^> %%d
    "%ADB%" -s %%d install -r "%APK%" >nul
  )
)

echo [*] Setting adb forward tcp:%PORT% on HOST %HOST% ...
"%ADB%" -s %HOST% forward tcp:%PORT% tcp:%PORT%

echo.
echo [OK] Done.
echo   1) On %HOST%: open ChatHub, "Create room", port %PORT%
echo   2) On the other emulator: "Join", IP 10.0.2.2, port %PORT%
endlocal
