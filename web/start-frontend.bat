@echo off
chcp 65001 >nul
title Emergency RAG Web - Frontend
cd /d "%~dp0"

echo ============================================
echo  Emergency Knowledge Base - Vue Frontend
echo ============================================
echo.
echo  Backend must be running on http://localhost:8080
echo  (Redis Stack in WSL2 + MySQL are required first)
echo.

if not exist "node_modules" (
    echo [1/2] node_modules not found, installing dependencies...
    call npm install --registry=https://registry.npmmirror.com
    if errorlevel 1 (
        echo.
        echo [ERROR] npm install failed. Check your network / Node.js installation.
        pause
        exit /b 1
    )
)

echo [2/2] Starting Vite dev server on http://localhost:5173 ...
echo.
call npm run dev

pause
