@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem  install-cert-windows.bat - put a certificate into the Windows certificate
rem  store, so the whole system trusts it (Chrome, Edge, git, curl, ...).
rem
rem  The app does NOT need this. The JVM has its own truststore and ignores
rem  the Windows store completely - setup-vpn-trust.bat fills that one in.
rem  Install here only when something outside Java has to trust the
rem  certificate too, for example a browser talking to a local HTTPS server.
rem
rem  Usage:
rem      install-cert-windows.bat <path-to-certificate.cer>
rem      install-cert-windows.bat <path-to-certificate.cer> -LocalMachine
rem
rem  By default it goes into the CURRENT USER's "Trusted Root Certification
rem  Authorities" store, which needs no administrator rights. Use
rem  -LocalMachine to install it for every user on the box (needs admin).
rem
rem  Windows may pop up a confirmation dialog for root certificates - click
rem  "Yes". Take it out again with:
rem
rem      certutil -delstore Root "OpenCode Test VPN Root CA"
rem ---------------------------------------------------------------------------

cd /d "%~dp0"

set "CA_FILE=%~1"
if not "%~1"=="" goto :have_file
set /p "CA_FILE=Full path to the certificate (.cer / .crt / .pem): "
:have_file

rem --- a path pasted with quotes would not be found
set "CA_FILE=%CA_FILE:"=%"

if "%CA_FILE%"=="" (
    echo ERROR: no certificate given.
    goto :fail
)
if not exist "%CA_FILE%" (
    echo ERROR: "%CA_FILE%" does not exist.
    goto :fail
)

set "SCOPE=CurrentUser"
if /i "%~2"=="-LocalMachine" set "SCOPE=LocalMachine"

echo.
echo === Installing into the Windows certificate store ===
echo     cert:  "%CA_FILE%"
echo     store: %SCOPE%\Root
echo.

if /i "%SCOPE%"=="LocalMachine" (
    call certutil -addstore -f Root "%CA_FILE%"
) else (
    call certutil -user -addstore -f Root "%CA_FILE%"
)
if errorlevel 1 goto :fail

echo.
echo === Done ===
echo.
echo The certificate is now trusted by Chrome, Edge, curl, git and friends.
echo.
echo The app does not read the Windows store - for the app run:
echo     setup-vpn-trust.bat "%CA_FILE%"
echo.
exit /b 0

:fail
echo.
echo === Failed - see the message above ===
echo.
exit /b 1
