@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem  doctor.bat - check that everything is in place.
rem
rem  Run this when the app does not start, or when it cannot reach the OpenCode
rem  gateway. It checks the JDK, the VPN truststore, the sherpa-onnx bindings,
rem  the native libraries, the local Maven repository, the built jar, the
rem  downloaded models - and it makes a real HTTPS call to the gateway with the
rem  truststore that is in force, so a PKIX failure shows up here too.
rem
rem  Usage:
rem      doctor.bat
rem      doctor.bat https://opencode.ai/zen/v1/models     check another URL
rem ---------------------------------------------------------------------------

cd /d "%~dp0"

set "PROBLEMS=0"
set "NOTES=0"
set "GATEWAY=%~1"
if "%GATEWAY%"=="" set "GATEWAY=https://opencode.ai/zen/v1/models"

echo ============================================================
echo  OpenCode - environment check
echo ============================================================
echo.

rem ------------------------------------------------------------------- java ---
echo --- java ---
call where java >nul 2>&1
if errorlevel 1 goto :no_java

set "JDK_HOME="
for /f "tokens=1,* delims==" %%A in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr "java.home"') do set "JDK_HOME=%%B"
if "%JDK_HOME:~0,1%"==" " set "JDK_HOME=%JDK_HOME:~1%"
if not defined JDK_HOME if defined JAVA_HOME set "JDK_HOME=%JAVA_HOME%"
if not defined JDK_HOME goto :no_jdk

echo   [ok] JDK: %JDK_HOME%
call java -version 2>&1
echo.

set "KEYTOOL=%JDK_HOME%\bin\keytool.exe"
if not exist "%KEYTOOL%" set "KEYTOOL=keytool"

rem ----------------------------------------------------------- the JDK trust ---
echo --- the JDK truststore ---
set "JDK_CACERTS=%JDK_HOME%\lib\security\cacerts"
if not exist "%JDK_CACERTS%" goto :no_jdk_cacerts
echo   [ok] %JDK_CACERTS%
echo.

rem ------------------------------------------------------- the VPN truststore ---
echo --- the VPN truststore (lib\cacerts) ---
if not exist "lib\cacerts" goto :no_truststore

call "%KEYTOOL%" -list -keystore "lib\cacerts" -storepass changeit >nul 2>&1
if errorlevel 1 goto :bad_truststore

set "ENTRIES=0"
call "%KEYTOOL%" -list -keystore "lib\cacerts" -storepass changeit > "lib\.doctor.list" 2>nul
for /f %%N in ('call findstr /c:"trustedCertEntry" "lib\.doctor.list"') do set /a ENTRIES+=1
del "lib\.doctor.list" >nul 2>&1
echo   [ok] lib\cacerts is a valid truststore, %ENTRIES% certificate(s)

call "%KEYTOOL%" -list -alias "vpn-root" -keystore "lib\cacerts" -storepass changeit >nul 2>&1
if errorlevel 1 goto :no_alias
echo   [ok] it holds the VPN root (alias: vpn-root)
echo.
goto :after_truststore

:no_alias
echo   [--] it holds no VPN root yet - run:  setup-vpn-trust.bat ^<the vpn ca^>
echo   [--] without it, every HTTPS call behind a TLS-inspecting VPN fails
set /a NOTES+=1
echo.
goto :after_truststore

:no_truststore
echo   [--] there is no lib\cacerts - that is fine if you are not behind a VPN
echo   [--] behind one, run:  setup-vpn-trust.bat ^<the vpn ca^>
set /a NOTES+=1
echo.
goto :after_truststore

:bad_truststore
echo   [X] lib\cacerts cannot be read - it is damaged. Delete it and run setup-vpn-trust.bat again.
set /a PROBLEMS+=1
echo.

:after_truststore

rem ------------------------------------------------------ the sherpa bindings ---
echo --- the sherpa-onnx JVM bindings ---
if not exist "lib\sherpa-onnx-jvm.jar" goto :no_jar
echo   [ok] lib\sherpa-onnx-jvm.jar
echo.
goto :after_jar

:no_jar
echo   [X] lib\sherpa-onnx-jvm.jar is missing - run:  setup.bat
set /a PROBLEMS+=1
echo.

:after_jar

rem ---------------------------------------------------------- the native libs ---
echo --- the native libraries ---
set "MISSING="
if not exist "lib\sherpa-onnx-jni.dll" set "MISSING=lib\sherpa-onnx-jni.dll "
if not exist "lib\onnxruntime.dll" set "MISSING=%MISSING%lib\onnxruntime.dll "
if not exist "lib\onnxruntime_providers_shared.dll" set "MISSING=%MISSING%lib\onnxruntime_providers_shared.dll "
if not exist "lib\sherpa-onnx-cxx-api.dll" set "MISSING=%MISSING%lib\sherpa-onnx-cxx-api.dll "
if "%MISSING%"=="" goto :libs_ok
echo   [X] missing: %MISSING%
echo       run:  setup.bat
set /a PROBLEMS+=1
echo.
goto :after_libs

:libs_ok
echo   [ok] all four DLLs are in lib\
echo.

:after_libs

rem ------------------------------------------------------- the Maven repository ---
echo --- the local Maven repository ---
set "M2=%USERPROFILE%\.m2\repository\com\k2fsa\sherpa\onnx\sherpa-onnx\1.12.1"
if not exist "%M2%\sherpa-onnx-1.12.1.jar" goto :no_m2
echo   [ok] com.k2fsa.sherpa.onnx:sherpa-onnx:1.12.1
echo.
goto :after_m2

:no_m2
echo   [X] the bindings are not in the local Maven repository - run:  setup.bat
set /a PROBLEMS+=1
echo.

:after_m2

rem ------------------------------------------------------------- the built jar ---
echo --- the built jar ---
if not exist "target\opencode-spring-boot-1.0.0.jar" goto :no_target
echo   [ok] target\opencode-spring-boot-1.0.0.jar
echo.
goto :after_target

:no_target
echo   [--] the jar is not built yet - run:  setup.bat   (or: mvnw.cmd clean package)
set /a NOTES+=1
echo.

:after_target

rem ------------------------------------------------------------------ the models ---
echo --- the speech-to-text models ---
if not exist "models" goto :no_models
set "MODELCOUNT=0"
for /f %%D in ('dir /b /ad "models" 2^>nul') do set /a MODELCOUNT+=1
if "%MODELCOUNT%"=="0" goto :no_models
echo   [ok] %MODELCOUNT% model(s) in models\
for /f %%D in ('dir /b /ad "models" 2^>nul') do echo        %%D
echo.
goto :after_models

:no_models
echo   [--] no models downloaded yet - the app starts, but it cannot transcribe
echo       the model manager downloads one on demand
set /a NOTES+=1
echo.

:after_models

rem ---------------------------------------------------------------- the gateway ---
echo --- the gateway over TLS ---
set "PROBE_OPTS="
if exist "lib\cacerts" set "PROBE_OPTS=-Djavax.net.ssl.trustStore=lib\cacerts -Djavax.net.ssl.trustStorePassword=changeit"

if not exist "scripts\TlsProbe.java" goto :no_probe
call java %PROBE_OPTS% "scripts\TlsProbe.java" "%GATEWAY%"
if errorlevel 1 goto :probe_failed
echo.
goto :after_probe

:probe_failed
echo   [X] the gateway could not be reached - if that says PKIX, the truststore
echo       above is the problem; if it says UnknownHostException, it is the network.
set /a PROBLEMS+=1
echo.
goto :after_probe

:no_probe
echo   [--] scripts\TlsProbe.java is missing
set /a NOTES+=1
echo.

:after_probe

rem --------------------------------------------------------------------- summary ---
echo ============================================================
if "%PROBLEMS%"=="0" (
    if "%NOTES%"=="0" (
        echo  Everything is in place.
    ) else (
        echo  No errors. %NOTES% note^(s^) above.
    )
) else (
    echo  %PROBLEMS% problem^(s^) found - see above.
)
echo ============================================================
echo.

if "%PROBLEMS%"=="0" exit /b 0
exit /b 1

rem ---------------------------------------------------------------------- errors ---
:no_java
echo   [X] java is not on the PATH. Install a JDK 17 or newer and put its bin
echo       directory on the PATH.
set /a PROBLEMS+=1
goto :fatal

:no_jdk
echo   [X] could not find a JDK. Set JAVA_HOME to a JDK 17 or newer and try again.
set /a PROBLEMS+=1
goto :fatal

:no_jdk_cacerts
echo   [X] %JDK_CACERTS% not found - the JDK looks incomplete.
set /a PROBLEMS+=1
goto :fatal

:fatal
echo.
echo ============================================================
echo  %PROBLEMS% problem^(s^) found.
echo ============================================================
exit /b 1
