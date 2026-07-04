@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo ========================================
echo   YOLO Tracker - Android Build
echo ========================================
echo.

:: 检查 Java
where java >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [错误] 未找到 Java，请确保已安装 JDK 17+
    pause
    exit /b 1
)

echo [1/2] 清理旧构建...
call gradlew.bat clean 2>nul

echo.
echo [2/2] 构建 Debug APK...
call gradlew.bat assembleDebug

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo   构建成功！
    echo   APK 位置:
    echo   app\build\outputs\apk\debug\app-debug.apk
    echo ========================================
) else (
    echo.
    echo [失败] 构建出错，请检查上方错误信息。
)

echo.
pause
