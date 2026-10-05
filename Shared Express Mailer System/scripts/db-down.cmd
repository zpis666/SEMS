@echo off
chcp 65001 >nul
setlocal
set "COMPOSE=%~dp0..\deploy\docker\docker-compose.yml"

echo 正在停止本地中间件...
docker compose -f "%COMPOSE%" down
echo.
echo [完成] 已停止，数据卷保留，下次启动数据仍在。
pause
endlocal
