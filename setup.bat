@echo off
setlocal EnableExtensions
rem ---------------------------------------------------------------------------
rem  setup.bat - one-time setup for the OpenCode Spring Boot app on Windows.
rem
rem  Run it from the project root:
rem      setup.bat
rem
rem  It does four things:
rem    1. downloads the sherpa-onnx JVM bindings into lib\
rem    2. downloads the Windows native libraries into lib\
rem    3. installs the bindings into the local Maven repository - they are
rem       published neither to Maven Central nor to JitPack, so the build
rem       cannot resolve them on its own
rem    4. builds the jar
rem
rem  Afterwards, start the app with:
rem      java -Djava.library.path=lib -jar target\opencode-spring-boot-1.0.0.jar
rem
rem  Re-running it is safe: anything already in place is left alone.
rem ---------------------------------------------------------------------------

cd /d "%~dp0"

set "VERSION=1.12.1"
set "JAR_NAME=sherpa-onnx-v%VERSION%-java21.jar"
set "JAR_URL=https://github.com/csukuangfj/sherpa-onnx/releases/download/v%VERSION%/%JAR_NAME%"
set "JNI_NAME=sherpa-onnx-v%VERSION%-win-x64-jni.tar.bz2"
set "JNI_URL=https://github.com/csukuangfj/sherpa-onnx/releases/download/v%VERSION%/%JNI_NAME%"
set "JNI_DIR=sherpa-onnx-v%VERSION%-win-x64-jni"

echo.
echo === OpenCode Spring Boot - Windows setup ===
echo     project: "%CD%"
echo.

rem ---------------------------------------------------------------- the tools
rem  "call" matters: a batch file invoked without it never returns control
call where curl.exe >nul 2>&1
if errorlevel 1 (
    echo ERROR: curl.exe was not found. It ships with Windows 10 1803 and later.
    goto :fail
)

call where tar.exe >nul 2>&1
if errorlevel 1 (
    echo ERROR: tar.exe was not found. It ships with Windows 10 1803 and later.
    goto :fail
)

if not exist "mvnw.cmd" (
    echo ERROR: mvnw.cmd is missing - run setup.bat from the project root.
    goto :fail
)

if not exist "lib" mkdir "lib"

rem --- a VPN that re-signs HTTPS breaks Maven's own downloads as well, because
rem     the build runs in a JVM of its own. Point it at the same truststore.
rem     (the working directory is the project root, so a relative path is fine)
if exist "lib\cacerts" set "MAVEN_OPTS=-Djavax.net.ssl.trustStore=lib\cacerts -Djavax.net.ssl.trustStorePassword=changeit"

rem --- a truncated download is worse than none: re-fetch anything implausibly small
set "JAR_BYTES=0"
if exist "lib\sherpa-onnx-jvm.jar" for %%A in ("lib\sherpa-onnx-jvm.jar") do set "JAR_BYTES=%%~zA"
if %JAR_BYTES% lss 10000 if exist "lib\sherpa-onnx-jvm.jar" del "lib\sherpa-onnx-jvm.jar"

set "DLL_BYTES=0"
if exist "lib\sherpa-onnx-jni.dll" for %%A in ("lib\sherpa-onnx-jni.dll") do set "DLL_BYTES=%%~zA"
if %DLL_BYTES% lss 100000 if exist "lib\sherpa-onnx-jni.dll" del "lib\sherpa-onnx-jni.dll"

rem --------------------------------------------------- 1. the JVM bindings ---
echo [1/4] The sherpa-onnx JVM bindings ...
if exist "lib\sherpa-onnx-jvm.jar" (
    echo      lib\sherpa-onnx-jvm.jar is already there.
) else (
    echo      downloading %JAR_NAME%
    call curl.exe -L --fail --output "lib\sherpa-onnx-jvm.jar" "%JAR_URL%"
    if errorlevel 1 goto :fail
)

rem ------------------------------------------------ 2. the native libraries ---
echo [2/4] The Windows native libraries ...
if exist "lib\sherpa-onnx-jni.dll" (
    echo      lib\sherpa-onnx-jni.dll is already there.
) else (
    if not exist "%JNI_NAME%" (
        echo      downloading %JNI_NAME%
        call curl.exe -L --fail --output "%JNI_NAME%" "%JNI_URL%"
        if errorlevel 1 goto :fail
    )
    echo      extracting
    call tar.exe -xf "%JNI_NAME%"
    if errorlevel 1 goto :fail
    copy /y "%JNI_DIR%\lib\*.dll" "lib\" >nul
    if errorlevel 1 goto :fail
    echo      lib\ now holds:
    dir /b "lib\*.dll"
)

rem ---------------------------- 3. install into the local Maven repository ---
echo [3/4] Installing the bindings into the local Maven repository ...
call mvnw.cmd install:install-file -Dfile=lib\sherpa-onnx-jvm.jar -DgroupId=com.k2fsa.sherpa.onnx -DartifactId=sherpa-onnx -Dversion=%VERSION% -Dpackaging=jar
if errorlevel 1 goto :fail

rem ------------------------------------------------------------- 4. the build ---
echo [4/4] Building ...
call mvnw.cmd clean package
if errorlevel 1 goto :fail

echo.
echo === Setup complete ===
echo.
echo Start the app with:
echo     java -Djava.library.path=lib -jar target\opencode-spring-boot-1.0.0.jar
echo.
echo Then open http://localhost:8080/voice-chat.html
echo.
exit /b 0

:fail
echo.
echo === Setup failed - see the message above ===
echo.
exit /b 1
