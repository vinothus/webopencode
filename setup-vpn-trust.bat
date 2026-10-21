@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem  setup-vpn-trust.bat - trust a VPN's TLS-inspection certificate.
rem
rem  Some VPNs (corporate, Cloudflare One / WARP) intercept HTTPS and re-sign
rem  traffic with their own root CA. Chrome and Edge trust it, because the VPN
rem  client installs it in the Windows certificate store. The JVM does not -
rem  it has its own truststore - so every HTTPS call fails with:
rem
rem      PKIX path building failed: sun.security.provider.certpath.
rem      SunCertPathBuilderException: unable to find valid certification path
rem      to requested target
rem
rem  This script copies the JDK truststore into lib\cacerts (so all the normal
rem  public CAs keep working) and adds the VPN root to the copy. Nothing inside
rem  the JDK is changed and no administrator rights are needed.
rem
rem  Usage:
rem      setup-vpn-trust.bat <path-to-the-vpn-root-certificate>
rem
rem  Export the certificate first: certmgr.msc -> Trusted Root Certification
rem  Authorities -> the VPN's CA -> Export Certificate -> .cer (Base-64).
rem  Cloudflare One / WARP publishes one as Cloudflare_WARP_Root_CA.pem.
rem
rem  Afterwards start the app with run.bat - it passes the truststore along.
rem ---------------------------------------------------------------------------

cd /d "%~dp0"

set "CA_FILE=%~1"
if not "%~1"=="" goto :have_file
set /p "CA_FILE=Full path to the VPN root certificate (.cer / .crt / .pem): "
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

set "ALIAS=vpn-root"
if not "%~2"=="" set "ALIAS=%~2"

rem ----------------------------------------------------------- locate the JDK ---
rem  java.home is the authority: it points at the JDK even when java.exe is
rem  reached through the Oracle javapath shim.
set "JDK_HOME="
for /f "tokens=1,* delims==" %%A in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr "java.home"') do set "JDK_HOME=%%B"
if "%JDK_HOME:~0,1%"==" " set "JDK_HOME=%JDK_HOME:~1%"
if not defined JDK_HOME if defined JAVA_HOME set "JDK_HOME=%JAVA_HOME%"

if not defined JDK_HOME (
    echo ERROR: could not find a JDK. Set JAVA_HOME and try again.
    goto :fail
)

set "CACERTS=%JDK_HOME%\lib\security\cacerts"
if not exist "%CACERTS%" (
    echo ERROR: %CACERTS% not found.
    echo        The JDK looks incomplete - try another JDK, or set JAVA_HOME.
    goto :fail
)

set "KEYTOOL=%JDK_HOME%\bin\keytool.exe"
if not exist "%KEYTOOL%" set "KEYTOOL=keytool"

echo.
echo === Trusting the VPN certificate ===
echo     JDK:      "%JDK_HOME%"
echo     CA:       "%CA_FILE%"
echo     alias:    %ALIAS%
echo.

if not exist "lib" mkdir "lib"

rem --- keep the public CAs: the truststore must stay a copy of the JDK's own
if exist "lib\cacerts" (
    echo     lib\cacerts already exists - adding to it
) else (
    echo     copying the JDK truststore to lib\cacerts ...
    copy /y "%CACERTS%" "lib\cacerts" >nul
    if errorlevel 1 goto :fail
)

rem --- drop any earlier copy of this alias, so the import never prompts
"%KEYTOOL%" -delete -alias "%ALIAS%" -keystore "lib\cacerts" -storepass changeit >nul 2>&1

echo     importing ...
"%KEYTOOL%" -importcert -alias "%ALIAS%" -file "%CA_FILE%" ^
    -keystore "lib\cacerts" -storepass changeit -noprompt
if errorlevel 1 goto :fail

echo.
echo     the truststore now holds:
"%KEYTOOL%" -list -v -alias "%ALIAS%" -keystore "lib\cacerts" -storepass changeit

echo.
echo === Done ===
echo.
echo Start the app with:
echo     run.bat
echo.
echo It adds -Djavax.net.ssl.trustStore=lib\cacerts automatically.
echo.
exit /b 0

:fail
echo.
echo === Failed - see the message above ===
echo.
exit /b 1
