@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem  run.bat - start the app.
rem
rem  Builds the jar first if it is not there yet, and passes the VPN truststore
rem  along when setup-vpn-trust.bat has created one.
rem
rem  Usage:
rem      run.bat                          start on the default port
rem      run.bat --server.port=9090        any Spring Boot argument works
rem ---------------------------------------------------------------------------

cd /d "%~dp0"

rem --- the truststore, if setup-vpn-trust.bat created one
set "JAVA_OPTS="
if exist "lib\cacerts" set "JAVA_OPTS=-Djavax.net.ssl.trustStore=lib\cacerts -Djavax.net.ssl.trustStorePassword=changeit"

if not exist "target\opencode-spring-boot-1.0.0.jar" (
    echo Building ...
    call mvnw.cmd clean package
    if errorlevel 1 (
        echo.
        echo === Build failed - see the message above ===
        exit /b 1
    )
)

echo.
echo === OpenCode Spring Boot ===
echo     http://localhost:8080/voice-chat.html
echo.
java %JAVA_OPTS% -Djava.library.path=lib -jar target\opencode-spring-boot-1.0.0.jar %*
exit /b %ERRORLEVEL%
