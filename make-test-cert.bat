@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem  make-test-cert.bat - mint a throw-away CA for testing the VPN fix.
rem
rem  You do not need this to fix a real VPN. It exists so you can check that
rem  setup-vpn-trust.bat and run.bat actually work, without having to hunt
rem  down a corporate CA first.
rem
rem  It creates, in lib\certs\:
rem     vpn-test-root.p12      the CA keystore      (password: changeit)
rem     vpn-test-root.cer      the CA certificate, DER   <- the file to trust
rem     vpn-test-root.pem      the CA certificate, base64
rem     vpn-test-server.p12    a server certificate signed by that CA
rem     vpn-test-server.cer    the same server certificate, DER
rem     vpn-test-server.pem    the same server certificate, base64
rem
rem  Then:
rem     setup-vpn-trust.bat lib\certs\vpn-test-root.cer
rem     run.bat
rem
rem  Nothing here is secret - it is a self-signed test certificate whose whole
rem  purpose is to be trusted by a test truststore.
rem ---------------------------------------------------------------------------

cd /d "%~dp0"

rem ------------------------------------------------------- locate the JDK -----
set "JDK_HOME="
for /f "tokens=1,* delims==" %%A in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr "java.home"') do set "JDK_HOME=%%B"
if "%JDK_HOME:~0,1%"==" " set "JDK_HOME=%JDK_HOME:~1%"
if not defined JDK_HOME if defined JAVA_HOME set "JDK_HOME=%JAVA_HOME%"
if not defined JDK_HOME (
    echo ERROR: could not find a JDK. Set JAVA_HOME and try again.
    goto :fail
)
set "KEYTOOL=%JDK_HOME%\bin\keytool.exe"
if not exist "%KEYTOOL%" set "KEYTOOL=keytool"

set "OUT=lib\certs"
set "PASS=changeit"

if not exist "%OUT%" mkdir "%OUT%"

rem --- start from scratch, so keytool never asks to overwrite an entry
if exist "%OUT%\vpn-test-root.p12" del /q "%OUT%\vpn-test-root.p12"
if exist "%OUT%\vpn-test-server.p12" del /q "%OUT%\vpn-test-server.p12"

echo.
echo === Making a test CA ===
echo     JDK:  "%JDK_HOME%"
echo     out:  "%OUT%"
echo.

echo     root CA ...
"%KEYTOOL%" -genkeypair -alias vpn-test-root -keyalg RSA -keysize 2048 -validity 3650 ^
    -dname "CN=OpenCode Test VPN Root CA, O=OpenCode, C=US" ^
    -ext bc=ca:true ^
    -keystore "%OUT%\vpn-test-root.p12" -storetype PKCS12 -storepass "%PASS%" -keypass "%PASS%"
if errorlevel 1 goto :fail

echo     exporting the CA ...
"%KEYTOOL%" -exportcert -alias vpn-test-root -keystore "%OUT%\vpn-test-root.p12" -storepass "%PASS%" -file "%OUT%\vpn-test-root.cer"
if errorlevel 1 goto :fail
"%KEYTOOL%" -exportcert -rfc -alias vpn-test-root -keystore "%OUT%\vpn-test-root.p12" -storepass "%PASS%" -file "%OUT%\vpn-test-root.pem"
if errorlevel 1 goto :fail

echo     server certificate ...
"%KEYTOOL%" -genkeypair -alias vpn-test-server -keyalg RSA -keysize 2048 -validity 3650 ^
    -dname "CN=localhost, O=OpenCode, C=US" ^
    -keystore "%OUT%\vpn-test-server.p12" -storetype PKCS12 -storepass "%PASS%" -keypass "%PASS%"
if errorlevel 1 goto :fail

"%KEYTOOL%" -certreq -alias vpn-test-server -keystore "%OUT%\vpn-test-server.p12" -storepass "%PASS%" -file "%OUT%\vpn-test-server.csr"
if errorlevel 1 goto :fail

"%KEYTOOL%" -gencert -alias vpn-test-root -keystore "%OUT%\vpn-test-root.p12" -storepass "%PASS%" ^
    -infile "%OUT%\vpn-test-server.csr" -outfile "%OUT%\vpn-test-server.cer" ^
    -ext san=dns:localhost,ip:127.0.0.1 -validity 3650
if errorlevel 1 goto :fail

"%KEYTOOL%" -gencert -rfc -alias vpn-test-root -keystore "%OUT%\vpn-test-root.p12" -storepass "%PASS%" ^
    -infile "%OUT%\vpn-test-server.csr" -outfile "%OUT%\vpn-test-server.pem" ^
    -ext san=dns:localhost,ip:127.0.0.1 -validity 3650
if errorlevel 1 goto :fail

echo.
echo === Done ===
echo.
echo The test CA is:
echo     "%OUT%\vpn-test-root.cer"      (DER - what setup-vpn-trust.bat wants)
echo     "%OUT%\vpn-test-root.pem"      (base64)
echo.
echo Try the whole flow with it:
echo     setup-vpn-trust.bat %OUT%\vpn-test-root.cer
echo     run.bat
echo.
echo To serve HTTPS with the signed certificate, pull the key out of the
echo keystore first:
echo     openssl pkcs12 -in %OUT%\vpn-test-server.p12 -nocerts -nodes -out server.key
echo.
exit /b 0

:fail
echo.
echo === Failed - see the message above ===
echo.
exit /b 1
