@echo off
chcp 65001 >nul
setlocal
set "COMPOSE=%~dp0..\deploy\docker\docker-compose.yml"
set "ENVFILE=%~dp0..\deploy\docker\.env"

echo [1/4] 检查 Docker 是否运行...
docker info >nul 2>&1
if errorlevel 1 (
  echo.
  echo [错误] Docker 未运行。请先启动 Docker Desktop，等状态变为 Running 后重试。
  echo.
  pause
  exit /b 1
)

echo [2/4] 检查端口占用（默认 3306/6379）...
set "MYSQL_PORT=3306"
set "REDIS_PORT=6379"
if exist "%ENVFILE%" (
  for /f "usebackq tokens=1,2 delims==" %%a in ("%ENVFILE%") do (
    if /i "%%a"=="MYSQL_PORT" set "MYSQL_PORT=%%b"
    if /i "%%a"=="REDIS_PORT" set "REDIS_PORT=%%b"
  )
)
docker inspect -f "{{.State.Status}}" "sems-mysql" 2>nul | findstr /c:"running" /c:"restarting" /c:"paused" >nul 2>&1
if not errorlevel 1 set "MYSQL_FREE=1"
if not defined MYSQL_FREE netstat -ano | findstr /c:":%MYSQL_PORT% " | findstr /c:"LISTENING" >nul 2>&1
if not defined MYSQL_FREE if errorlevel 1 set "MYSQL_FREE=1"
docker inspect -f "{{.State.Status}}" "sems-redis" 2>nul | findstr /c:"running" /c:"restarting" /c:"paused" >nul 2>&1
if not errorlevel 1 set "REDIS_FREE=1"
if not defined REDIS_FREE netstat -ano | findstr /c:":%REDIS_PORT% " | findstr /c:"LISTENING" >nul 2>&1
if not defined REDIS_FREE if errorlevel 1 set "REDIS_FREE=1"
set "PORT_BUSY="
if not defined MYSQL_FREE set "PORT_BUSY=1"
if not defined REDIS_FREE set "PORT_BUSY=1"
if defined PORT_BUSY (
  echo.
  echo [错误] 端口已被其它程序占用，请停止占用程序或改 deploy\docker\.env 的 MYSQL_PORT / REDIS_PORT 后重试（若尚无 .env，请先从 .env.example 复制为 .env）。
  echo.
  pause
  exit /b 1
)

echo [3/4] 启动 MySQL 8.0 与 Redis 7...
docker compose -f "%COMPOSE%" up -d
if errorlevel 1 (
  echo.
  echo [错误] 启动失败，请查看上方 docker 输出。
  echo        若为端口占用，请检查 3306/6379，或改 deploy\docker\.env 的 MYSQL_PORT / REDIS_PORT 后重试（若尚无 .env，请先从 .env.example 复制为 .env）。
  pause
  exit /b 1
)

echo [4/4] 当前状态：
docker compose -f "%COMPOSE%" ps
echo.
echo    MySQL  localhost:%MYSQL_PORT%   库 sems   用户 sems / root
echo    Redis  localhost:%REDIS_PORT%   已开启 requirepass
echo    口令见 deploy\docker\.env.example
echo.
pause
endlocal
exit /b 0
