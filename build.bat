@echo off
setlocal
cd /d "%~dp0"

set "ALIAS=mykey"
set "APK_DIR=app\build\outputs\apk"

echo ===== OldGemAI: build and sign =====
echo.

if not exist "my.keystore" (
    echo ERROR: my.keystore not found in the project root.
    echo Create it once with this command, then run the script again:
    echo.
    echo keytool -genkeypair -v -keystore my.keystore -alias mykey -keyalg RSA -keysize 2048 -validity 10000
    echo.
    pause
    exit /b 1
)

if not exist "uber-apk-signer.jar" (
    echo ERROR: uber-apk-signer.jar not found in the project root.
    echo.
    pause
    exit /b 1
)

rem Passwords are asked first, so the build can run without you. Input is hidden.
set "KSPASS="
set "KEYPASS="
for /f "delims=" %%i in ('powershell -NoProfile -Command "$s = Read-Host 'Keystore password' -AsSecureString; [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))"') do set "KSPASS=%%i"
if not defined KSPASS (
    echo ERROR: keystore password is empty.
    echo.
    pause
    exit /b 1
)
for /f "delims=" %%i in ('powershell -NoProfile -Command "$s = Read-Host 'Key password (Enter = same as keystore password)' -AsSecureString; [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))"') do set "KEYPASS=%%i"
if not defined KEYPASS set "KEYPASS=%KSPASS%"

echo.
echo [1/3] Building...
call gradlew.bat clean assembledebug -Pandroid.buildToolsVersion=30.0.3
if errorlevel 1 (
    echo.
    echo ERROR: build failed.
    pause
    exit /b 1
)

if not exist "%APK_DIR%\app-debug.apk" (
    echo.
    echo ERROR: %APK_DIR%\app-debug.apk not found after the build.
    pause
    exit /b 1
)

echo.
echo [2/3] Copying my.keystore and uber-apk-signer.jar to %APK_DIR% ...
copy /y "my.keystore" "%APK_DIR%\" >nul
copy /y "uber-apk-signer.jar" "%APK_DIR%\" >nul

echo.
echo [3/3] Signing...
pushd "%APK_DIR%"
java -jar uber-apk-signer.jar -a app-debug.apk --ks my.keystore --ksAlias %ALIAS% --ksKeyPass "%KEYPASS%" --ksPass "%KSPASS%" --v2SigningEnabled true
set "SIGN_RESULT=%errorlevel%"
popd

if not "%SIGN_RESULT%"=="0" (
    echo.
    echo ERROR: signing failed. Check the passwords and the alias ^(%ALIAS%^).
    pause
    exit /b 1
)

echo.
echo DONE. Signed APK:
echo %CD%\%APK_DIR%\app-debug-aligned-signed.apk
echo.
pause
