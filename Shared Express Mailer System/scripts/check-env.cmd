@echo off
chcp 65001 >nul
echo ===== Shared Express Mailer System 开发环境自检 =====
echo.

echo --- JAVA_HOME ---
echo %JAVA_HOME%
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo [警告] JAVA_HOME 无效或未设置，后端无法构建。
) else (
  "%JAVA_HOME%\bin\java" -version 2>&1
)
echo.

echo --- Node / npm（前端）---
node -v 2>&1
npm -v 2>&1
echo.

echo --- Git ---
git --version 2>&1
echo.

echo --- Docker（本地中间件）---
docker --version 2>&1
docker compose version 2>&1
echo.

echo [说明] PATH 上的 java 可能指向 Oracle Java 8，本项目不使用；
echo        后端构建一律以 JAVA_HOME 指向的 JDK 21 为准。
echo.
pause
