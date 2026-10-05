@echo off
chcp 65001 >nul
setlocal
set "COMPOSE=%~dp0..\deploy\docker\docker-compose.yml"

echo [警告] 即将删除本地 MySQL / Redis 的全部数据卷并重新初始化。
echo        此操作不可恢复，仅用于本地开发环境。
echo.
set /p CONFIRM=确认请输入 yes 后回车：
if /i not "%CONFIRM%"=="yes" (
  echo 已取消。
  pause
  exit /b 0
)

echo 正在删除数据卷...
docker compose -f "%COMPOSE%" down -v
echo 正在重新初始化...
docker compose -f "%COMPOSE%" up -d
if errorlevel 1 (
  echo [错误] 重建失败，请查看上方 docker 输出。
  pause
  exit /b 1
)

echo.
echo [完成] 已重建，deploy\sql\ 下的脚本已在本次初始化中导入。
docker compose -f "%COMPOSE%" ps
pause
endlocal
