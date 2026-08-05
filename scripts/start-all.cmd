@echo off
rem ============================================================
rem QuantumLink IM - server one-click start
rem Starts: Redis / RocketMQ(namesrv+broker) / Nacos / MinIO /
rem         im-chat / im-connect x2
rem Usage: scripts\start-all.cmd
rem ============================================================
setlocal enabledelayedexpansion

echo ==================== QuantumLink start-all ====================

rem ---------- 1. Redis ----------
netstat -ano | findstr ":6379" >nul 2>&1 && (
  echo [1/7] Redis already running
) || (
  echo [1/7] starting Redis ...
  start "redis-server" /D "F:\Study\Redis4" redis-server.exe redis.windows.conf
  ping -n 2 127.0.0.1 >nul
)

rem ---------- 2. RocketMQ namesrv ----------
netstat -ano | findstr ":9876" >nul 2>&1 && (
  echo [2/7] RocketMQ namesrv already running
) || (
  echo [2/7] starting RocketMQ namesrv ...
  start "rmq-namesrv" cmd /c "cd /d F:\Study\RocketMQ\rocketmq-all-5.3.1-bin-release\bin && F:\Study\JDK8\bin\java.exe -Xms256m -Xmx256m -cp ..\lib\* org.apache.rocketmq.namesrv.NamesrvStartup"
  ping -n 8 127.0.0.1 >nul
)

rem ---------- 3. RocketMQ broker ----------
netstat -ano | findstr ":10911" >nul 2>&1 && (
  echo [3/7] RocketMQ broker already running
) || (
  echo [3/7] starting RocketMQ broker ...
  start "rmq-broker" cmd /c "cd /d F:\Study\RocketMQ\rocketmq-all-5.3.1-bin-release\bin && F:\Study\JDK8\bin\java.exe -Xms512m -Xmx512m -Drocketmq.home.dir=F:\Study\RocketMQ\rocketmq-all-5.3.1-bin-release -cp ..\lib\* org.apache.rocketmq.broker.BrokerStartup -n 127.0.0.1:9876"
  ping -n 15 127.0.0.1 >nul
)

rem ---------- 4. Nacos ----------
netstat -ano | findstr ":8850" >nul 2>&1 && (
  echo [4/7] Nacos already running
) || (
  echo [4/7] starting Nacos standalone on 8850 ...
  start "nacos" /D "F:\Study\Nacos\nacos" "D:\jdk17\bin\java.exe" -Xms512m -Xmx512m -Dserver.port=8850 -Dnacos.standalone=true -jar target\nacos-server.jar
  echo      waiting ~40s for Nacos ...
  ping -n 40 127.0.0.1 >nul
)

rem ---------- 5. MinIO ----------
netstat -ano | findstr ":9000" >nul 2>&1 && (
  echo [5/7] MinIO already running
) || (
  echo [5/7] starting MinIO ...
  set "MINIO_ROOT_USER=minioadmin"
  set "MINIO_ROOT_PASSWORD=minioadmin"
  start "minio" /D "F:\Study\MinIO" minio.exe server F:\Study\MinIO\quantumlink-data --address 127.0.0.1:9000
  ping -n 5 127.0.0.1 >nul
)

rem ---------- 6. im-chat ----------
netstat -ano | findstr ":8081" >nul 2>&1 && (
  echo [6/7] im-chat already running
) || (
  echo [6/7] starting im-chat ...
  start "im-chat" "D:\jdk17\bin\java.exe" -jar "E:\QIUZHAO\IM\im-chat\target\im-chat-1.0.0-SNAPSHOT.jar"
  echo      waiting ~35s for im-chat ...
  ping -n 35 127.0.0.1 >nul
)

rem ---------- 7. im-connect x2 ----------
netstat -ano | findstr ":19001" >nul 2>&1 || (
  echo [7/7] starting im-connect 19001 ...
  start "im-connect-19001" "D:\jdk17\bin\java.exe" -Dim.connect.port=19001 -jar "E:\QIUZHAO\IM\im-connect\target\im-connect-1.0.0-SNAPSHOT.jar"
)
netstat -ano | findstr ":19002" >nul 2>&1 || (
  echo [7/7] starting im-connect 19002 ...
  start "im-connect-19002" "D:\jdk17\bin\java.exe" -Dim.connect.port=19002 -jar "E:\QIUZHAO\IM\im-connect\target\im-connect-1.0.0-SNAPSHOT.jar"
)
netstat -ano | findstr ":19001" >nul 2>&1 && echo [7/7] im-connect x2 already running
ping -n 8 127.0.0.1 >nul

echo.
echo ==================== Done ====================
echo   MySQL   : 3306  (Windows service, assumed running)
echo   Redis   : 6379
echo   RocketMQ: 9876(namesrv) / 10911(broker)
echo   Nacos   : 8850
echo   MinIO   : 9000
echo   im-chat : 8081
echo   im-connect: 19001 / 19002
echo.
echo Verify: curl http://127.0.0.1:8081/api/connects
echo Client : scripts\start-client.cmd
endlocal
