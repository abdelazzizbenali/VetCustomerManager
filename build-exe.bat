@echo off
REM =====================================================================
REM  VetCustomerManager - build the ready-to-use Windows .exe installer
REM  Online-only edition (Supabase + local SQLite cache, no clinic server)
REM
REM  What you need ONCE on your Windows PC:
REM    1. JDK 25 LTS (https://adoptium.net -> Temurin 25) with JAVA_HOME set
REM    2. Apache Maven (https://maven.apache.org) - "mvn" on the PATH
REM       (Inno Setup installs itself automatically below, via winget)
REM
REM  Then run this file from the project folder. The installer appears in
REM  dist\installer\  as  VetCustomerManager-Setup-4.0.0.exe.
REM  It is a FRESH-INSTALLER: it removes any older version first, and a
REM  full uninstall deletes the program AND the per-user data folder.
REM  The app is self-contained: NO Java needed on the computers that use it.
REM =====================================================================
setlocal EnableExtensions
cd /d "%~dp0"

set APP_VERSION=4.0.0
set JFX_VERSION=25.0.4
set INNO_DIR=C:\Program Files (x86)\Inno Setup 6

echo [0/7] CLEAN slate - wiping previous build output (target, dist)...
if exist target rmdir /s /q target
if exist dist rmdir /s /q dist

echo [1/7] Building the frontend jar (Maven)...
call mvn -B -ntp -DskipTests package
if errorlevel 1 goto :fail

echo [2/7] Collecting runtime dependencies...
if not exist target\pkg mkdir target\pkg
call mvn -B -ntp -DskipTests -DincludeScope=runtime dependency:copy-dependencies -DoutputDirectory="%CD%\target\pkg"
if errorlevel 1 goto :fail
copy /Y frontend\target\vetms-%APP_VERSION%.jar target\pkg\ >nul
if errorlevel 1 goto :fail

echo      plus optional Dynamsoft premium scanner jar (best effort)...
powershell -NoProfile -Command "$b='https://download2.dynamsoft.com/maven/dbr/jar/com/dynamsoft/dbr'; $vs=@(); try { $x=[xml](Invoke-WebRequest -UseBasicParsing \"$b/maven-metadata.xml\").Content; $vs += @($x.metadata.versioning.versions.version | Select-Object -Last 2) } catch {}; $vs += @('9.6.40.1','9.6.40','9.6.0'); foreach($v in $vs | ? {$_}) { try { Invoke-WebRequest -UseBasicParsing \"$b/$v/dbr-$v.jar\" -OutFile 'target\pkg\dbr.jar'; if((Get-Item 'target\pkg\dbr.jar').Length -gt 500000) { break } } catch {} }"
if exist target\pkg\dbr.jar echo      Dynamsoft jar bundled - premium engine ready.
if not exist target\pkg\dbr.jar echo      Dynamsoft jar skipped - built-in engine will still scan fine.

echo [3/7] Downloading JavaFX jmods (LTS 25.0.4, fallback 26.0.1)...
powershell -NoProfile -Command "$ok=$false; foreach($v in @('25.0.4','26.0.1')){ try { Invoke-WebRequest -Uri \"https://download2.gluonhq.com/openjfx/$v/openjfx-${v}_windows-x64_bin-jmods.zip\" -OutFile 'target\jmods.zip'; if((Get-Item 'target\jmods.zip').Length -gt 5MB){ Set-Content -NoNewline target\jmods.version $v; $ok=$true; break } } catch {} }; if(-not $ok){ exit 1 }"
if errorlevel 1 goto :jmods_manual
powershell -NoProfile -Command "Expand-Archive -Force 'target\jmods.zip' 'target\jmods'"
if errorlevel 1 goto :jmods_manual
set /p JFX_VERSION=<target\jmods.version
if exist target\jmods\javafx-jmods-%JFX_VERSION% goto :jmods_ok
powershell -NoProfile -Command "(Get-ChildItem target\jmods -Directory | ? {$_.Name -like 'javafx-jmods-*'} | Select-Object -First 1).Name -replace 'javafx-jmods-',''" > target\jmods.version
set /p JFX_VERSION=<target\jmods.version
if exist target\jmods\javafx-jmods-%JFX_VERSION% goto :jmods_ok

:jmods_manual
echo.
echo  !!! Could not download/extract the JavaFX jmods automatically.
echo      1. Go to https://gluonhq.com/products/javafx/ and download the
echo         jmods for JavaFX 25.0.4 - Windows x64
echo      2. Extract the zip so this folder exists:
echo         %CD%\target\jmods\javafx-jmods-25.0.4
echo      3. Re-run build-exe.bat
goto :fail

:jmods_ok

echo [4/7] Creating the embedded Java runtime with jlink...
if exist target\runtime rmdir /s /q target\runtime
jlink --module-path "target\jmods\javafx-jmods-%JFX_VERSION%;%JAVA_HOME%\jmods" --add-modules java.base,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,javafx.base,javafx.graphics,javafx.controls,javafx.swing,javafx.fxml,jdk.crypto.ec,jdk.crypto.mscapi,jdk.jfr,jdk.localedata,jdk.zipfs,jdk.unsupported --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output target\runtime
if errorlevel 1 goto :fail

echo [5/7] Assembling the app folder with jpackage...
jpackage --verbose --type app-image --input target\pkg --main-jar vetms-%APP_VERSION%.jar --main-class dev.parent.Launcher --runtime-image target\runtime --name VetCustomerManager --app-version %APP_VERSION% --vendor "AAB" --description "Veterinary Customer Manager - Supabase online edition" --icon packaging\icon.ico --java-options "-Dfile.encoding=UTF-8" --dest dist\app-image
if errorlevel 1 goto :fail

echo [6/7] Making sure Inno Setup 6 is available (installs via winget once)...
if exist "%INNO_DIR%\ISCC.exe" goto :inno_ok
where ISCC.exe >nul 2>&1
if errorlevel 1 goto :inno_install
goto :inno_ok
:inno_install
echo      Inno Setup not found - installing it now (needs admin once)...
winget install --id JRSoftware.InnoSetup -e --silent --accept-package-agreements --accept-source-agreements
if exist "%INNO_DIR%\ISCC.exe" goto :inno_ok
echo.
echo  !!! Inno Setup could not be installed automatically.
echo      Install it once from https://jrsoftware.org/isdl.php (Inno Setup 6)
echo      then re-run build-exe.bat
goto :fail

:inno_ok

echo [7/7] Compiling the installer with Inno Setup...
set "PATH=%PATH%;%INNO_DIR%"
ISCC.exe packaging\installer.iss
if errorlevel 1 goto :fail

echo.
echo  ============================================================
echo   DONE! Your installer is ready:
echo      dist\installer\VetCustomerManager-Setup-%APP_VERSION%.exe
echo   It kills any running copy, removes the old one, installs fresh.
echo   Users just run it - nothing else to install, no Java needed.
echo  ============================================================
goto :done

:fail
echo.
echo  *** BUILD FAILED - read the message above to see what is missing ***
endlocal
exit /b 1

:done
endlocal
exit /b 0
