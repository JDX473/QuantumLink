@echo off
rem QuantumLink local middleware start script (Win)
rem Starts RocketMQ namesrv + broker, Redis. MySQL assumed running as Windows service.
rem Usage: scripts\start-middleware.cmd

echo ============ RocketMQ ============
set ROCKETMQ_HOME=F:\Study\RocketMQ\rocketmq-all-5.3.1-bin-release
set JAVA_HOME=F:\Study\JDK8

echo [1/4] starting Namesrv ...
start "rmq-namesrv" cmd /c "cd /d %ROCKETMQ_HOME%\bin && mqnamesrv.cmd"

timeout /t 6 /nobreak >nul

echo [2/4] starting Broker ...
start "rmq-broker" cmd /c "cd /d %ROCKETMQ_HOME%\bin && mqbroker.cmd -n localhost:9876"

echo ============ Redis ============
echo [3/4] starting Redis ...
start "redis-server" cmd /c "cd /d F:\Study\Redis4 && redis-server.exe redis.windows.conf"

echo [4/4] waiting 8s for all services to stabilize ...
timeout /t 8 /nobreak >nul

echo.
echo All done. Verify:
echo   RocketMQ namesrv : localhost:9876
echo   RocketMQ broker  : localhost:10911
echo   Redis            : localhost:6379
echo   MySQL            : localhost:3306 (Windows service, assumed running)
endlocal
