@echo off
setlocal

netstat -ano | findstr ":8081" >nul 2>&1
if errorlevel 1 goto server_down
echo [OK] server ready (im-chat 8081)
goto check_deps

:server_down
echo [!!] im-chat 8081 not running - server not ready!
echo       Please run first: scripts\start-all.cmd
pause
exit /b 1

:check_deps
if exist "E:\QIUZHAO\IM\clients\desktop\node_modules\electron" goto launch
echo [..] installing desktop deps (npm install)...
pushd E:\QIUZHAO\IM\clients\desktop
call npm install
popd

:launch
echo [OK] launching desktop client...
cd /d E:\QIUZHAO\IM\clients\desktop
start "electron-client" "E:\QIUZHAO\IM\clients\desktop\node_modules\.bin\electron.cmd" . --disable-gpu

echo QuantumLink desktop started - register/login and chat.
endlocal
