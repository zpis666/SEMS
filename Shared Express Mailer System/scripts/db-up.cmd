@echo off
chcp 65001 >nul
setlocal
set "COMPOSE=%~dp0..\deploy\docker\docker-compose.yml"

echo [1/3] 检查 Docker 是否运行...
docker info >nul 2>&1
if errorlevel 1 (
  echo.
  echo [错误] Docker 未运行。请先启动 Docker Desktop，等状态变为 Running 后重试。
  echo.
  pause
  exit /b 1
)

echo [2/3] 启动 MySQL 8.0 与 Redis 7...
docker compose -f "%COMPOSE%" up -d
if errorlevel 1 (
  echo.
  echo [错误] 启动失败，请查看上方 docker 输出。
  pause
  exit /b 1
)

echo [3/3] 当前状态：
docker compose -f "%COMPOSE%" ps
echo.
echo    MySQL  localhost:3306   库 sems   用户 sems / root
echo    Redis  localhost:6379   已开启 requirepass
echo    口令见 deploy\docker\.env.example
echo.
pause
endlocal
