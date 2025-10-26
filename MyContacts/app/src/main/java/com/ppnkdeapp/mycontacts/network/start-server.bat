@echo off
echo Starting Call Server...

REM Проверяем наличие Node.js
node --version >nul 2>&1
if %errorlevel% neq 0 (
    echo Node.js not found! Please install Node.js from https://nodejs.org/
    echo.
    echo After installing Node.js, run:
    echo   npm install
    echo   npm start
    pause
    exit /b 1
)

REM Проверяем наличие node_modules
if not exist "node_modules" (
    echo Installing dependencies...
    npm install
    if %errorlevel% neq 0 (
        echo Failed to install dependencies!
        pause
        exit /b 1
    )
)

echo Starting server...
node server-simple.js

pause
