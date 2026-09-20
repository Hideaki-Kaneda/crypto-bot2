@echo off
setlocal enabledelayedexpansion
chcp 65001 > nul

if "%~1"=="" (
    echo [ERROR] API_KEY が指定されていません。
    echo 使用方法: scripts\encrypt-secrets.bat ^<API_KEY^> ^<API_SECRET^>
    exit /b 1
)
if "%~2"=="" (
    echo [ERROR] API_SECRET が指定されていません。
    echo 使用方法: scripts\encrypt-secrets.bat ^<API_KEY^> ^<API_SECRET^>
    exit /b 1
)

set API_KEY=%~1
set API_SECRET=%~2

if "%CRYPTO_MASTER_KEY%"=="" (
    echo [ERROR] 環境変数 CRYPTO_MASTER_KEY が未設定です。
    echo   set CRYPTO_MASTER_KEY=your_strong_master_password
    exit /b 1
)

set SCRIPT_DIR=%~dp0
set PROJECT_DIR=%SCRIPT_DIR%..
set SECRET_PROPS=%PROJECT_DIR%\src\main\resources\secret.properties
set JAR_PATH=%PROJECT_DIR%\target\crypto-bot2-1.0.0.jar
set TEMP_OUT=%TEMP%\cryptobot2_enc.tmp

if not exist "%JAR_PATH%" (
    echo [ERROR] JAR が見つかりません: %JAR_PATH%
    echo   先に "mvn clean package -DskipTests" を実行してください。
    exit /b 1
)

echo API KEY を暗号化しています...
java -DCRYPTO_MASTER_KEY=%CRYPTO_MASTER_KEY% -Dloader.main=com.example.cryptobot2.util.EncryptCli -jar "%JAR_PATH%" "%API_KEY%" > "%TEMP_OUT%" 2>&1
if errorlevel 1 (
    echo [ERROR] API KEY の暗号化に失敗しました。
    type "%TEMP_OUT%"
    del "%TEMP_OUT%" 2>nul
    exit /b 1
)
set ENC_KEY=
for /f "usebackq delims=" %%i in ("%TEMP_OUT%") do (
    echo %%i | findstr /b "ENC(" > nul && set ENC_KEY=%%i
)
del "%TEMP_OUT%" 2>nul
if "!ENC_KEY!"=="" (
    echo [ERROR] API KEY の暗号化結果が取得できませんでした。
    exit /b 1
)
echo [OK] API KEY 完了。

echo API SECRET を暗号化しています...
java -DCRYPTO_MASTER_KEY=%CRYPTO_MASTER_KEY% -Dloader.main=com.example.cryptobot2.util.EncryptCli -jar "%JAR_PATH%" "%API_SECRET%" > "%TEMP_OUT%" 2>&1
if errorlevel 1 (
    echo [ERROR] API SECRET の暗号化に失敗しました。
    type "%TEMP_OUT%"
    del "%TEMP_OUT%" 2>nul
    exit /b 1
)
set ENC_SECRET=
for /f "usebackq delims=" %%i in ("%TEMP_OUT%") do (
    echo %%i | findstr /b "ENC(" > nul && set ENC_SECRET=%%i
)
del "%TEMP_OUT%" 2>nul
if "!ENC_SECRET!"=="" (
    echo [ERROR] API SECRET の暗号化結果が取得できませんでした。
    exit /b 1
)
echo [OK] API SECRET 完了。

(
    echo # secret.properties
    echo # This file is excluded from Git. Do not commit.
    echo.
    echo gmo.api.key=!ENC_KEY!
    echo gmo.api.secret=!ENC_SECRET!
) > "%SECRET_PROPS%"

if errorlevel 1 (
    echo [ERROR] secret.properties の書き込みに失敗しました。
    exit /b 1
)

echo [OK] secret.properties を更新しました: %SECRET_PROPS%
echo このファイルは Git にコミットしないでください。

endlocal
exit /b 0
