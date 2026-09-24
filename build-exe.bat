@echo off
REM =====================================================================
REM  VetCustomerManager - build the ready-to-use Windows .exe installer
REM  Online-only edition (Supabase + local SQLite cache, no clinic server)
REM
REM  What you need ONCE on your Windows PC:
REM    1. JDK 21 LTS (https://adoptium.net -> Temurin 21) with JAVA_HOME set
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
set JFX_VERSION=21.0.5
set INNO_DIR=C:\Program Files (x86)\Inno Setup 6
set INNO_DIR2=C:\Program Files\Inno Setup 6

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
powershell -NoProfile -Command "$bh='https://download2.dynamsoft.com/maven/dbr/jar/com/dynamsoft/dbr'; $bp='http://download2.dynamsoft.com/maven/dbr/jar/com/dynamsoft/dbr'; $vs=@(); foreach($b in @($bh,$bp)){ try{ $x=[xml](Invoke-WebRequest -UseBasicParsing \"${b}/maven-metadata.xml\" -TimeoutSec 12).Content; $vs+=@($x.metadata.versioning.versions.version | Select-Object -Last 2); if($vs.Count -gt 0){break} }catch{} } $vs+=@('9.6.40.1','9.6.40','9.6.0','11.6.3000'); $ok=$false; foreach($v in ($vs | ? {$_} | Select-Object -Unique)){ foreach($b in @($bh,$bp)){ try{ Invoke-WebRequest -UseBasicParsing \"${b}/${v}/dbr-${v}.jar\" -OutFile 'target\pkg\dbr.jar' -TimeoutSec 20; if((Get-Item 'target\pkg\dbr.jar').Length -gt 400000){ $ok=$true; break } }catch{} } if($ok){break} } if(-not $ok){ try{ mvn -B -ntp dependency:get '-Dartifact=com.dynamsoft:dbr:9.6.40' '-DremoteRepositories=dbr::default::https://download2.dynamsoft.com/maven/dbr/jar' '-Dtransitive=false' | Out-Null; $loc=\"$env:USERPROFILE\.m2\repository\com\dynamsoft\dbr\9.6.40\dbr-9.6.40.jar\"; if(Test-Path $loc){ Copy-Item $loc 'target\pkg\dbr.jar' -Force } }catch{} }"
if exist target\pkg\dbr.jar echo      Dynamsoft jar bundled - premium engine ready.
if not exist target\pkg\dbr.jar echo      Dynamsoft jar skipped - built-in engine will still scan fine.

echo [3/7] Downloading JavaFX jmods (LTS 21.0.5, fallback 25.0.4)...
if exist target\jmods\javafx-jmods-21.0.5 goto :jmods_ok
if exist target\jmods\javafx-jmods-25.0.4 goto :jmods_ok
:: Re-use already downloaded zip if present
if exist target\jmods.zip (
  echo      Found existing target\jmods.zip, extracting...
  powershell -NoProfile -Command "Expand-Archive -Force 'target\jmods.zip' 'target\jmods' 2>$null"
  if exist target\jmods\javafx-jmods-21.0.5 goto :jmods_ok
)
powershell -NoProfile -Command "$ok=$false; foreach($v in @('21.0.5','25.0.4')){ try { Invoke-WebRequest -UseBasicParsing -Uri \"https://download2.gluonhq.com/openjfx/$v/openjfx-${v}_windows-x64_bin-jmods.zip\" -OutFile 'target\jmods.zip'; if(Test-Path 'target\jmods.zip'){ if((Get-Item 'target\jmods.zip').Length -gt 5MB){ Set-Content -NoNewline target\jmods.version $v; $ok=$true; break } } } catch { Write-Host \"jmods $v failed: $_\" } }; if(-not $ok){ exit 1 }"
if %errorlevel%==0 goto :jmods_extract
echo      powershell download failed, trying pwsh (PowerShell 7)...
where pwsh >nul 2>&1
if %errorlevel%==0 (
  pwsh -NoProfile -Command "$ok=$false; foreach($v in @('21.0.5','25.0.4')){ try { Invoke-WebRequest -Uri \"https://download2.gluonhq.com/openjfx/$v/openjfx-${v}_windows-x64_bin-jmods.zip\" -OutFile 'target\jmods.zip' -SkipCertificateCheck -TimeoutSec 30; if(Test-Path 'target\jmods.zip'){ if((Get-Item 'target\jmods.zip').Length -gt 5MB){ Set-Content -NoNewline target\jmods.version $v; $ok=$true; break } } } catch { Write-Host \"jmods $v pwsh failed: $_\" } }; if(-not $ok){ exit 1 }"
  if %errorlevel%==0 goto :jmods_extract
)
goto :jmods_manual
:jmods_extract
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
echo         jmods for JavaFX 21.0.5 - Windows x64
echo      2. Extract the zip so this folder exists:
echo         %CD%\target\jmods\javafx-jmods-21.0.5
echo      3. Re-run build-exe.bat
goto :fail

:jmods_ok

echo [4/7] Creating the embedded Java runtime with jlink...
if exist target\runtime rmdir /s /q target\runtime
jlink --module-path "target\jmods\javafx-jmods-%JFX_VERSION%;%JAVA_HOME%\jmods" --add-modules java.base,java.desktop,java.instrument,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,javafx.base,javafx.graphics,javafx.controls,javafx.swing,javafx.fxml,jdk.crypto.ec,jdk.crypto.mscapi,jdk.jfr,jdk.localedata,jdk.zipfs,jdk.unsupported --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output target\runtime
if errorlevel 1 goto :fail

echo [5/7] Assembling the app folder with jpackage...
jpackage --verbose --type app-image --input target\pkg --main-jar vetms-%APP_VERSION%.jar --main-class dev.parent.Launcher --runtime-image target\runtime --name VetCustomerManager --app-version %APP_VERSION% --vendor "AAB" --description "Veterinary Customer Manager - Supabase online edition" --icon packaging\icon.ico --java-options "-Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED" --dest dist\app-image
if errorlevel 1 goto :fail

echo [6/7] Making sure Inno Setup 6 is available (installs via winget once)...
:: Check all known locations + PATH before trying to install
if exist "%INNO_DIR%\ISCC.exe" goto :inno_ok
if exist "%INNO_DIR2%\ISCC.exe" set "INNO_DIR=%INNO_DIR2%" & goto :inno_ok
where ISCC.exe >nul 2>&1
if %errorlevel%==0 goto :inno_ok
:: Try to locate via winget list / registry
for /f "delims=" %%i in ('where ISCC.exe 2^>nul') do set "INNO_DIR=%%~dpi" & goto :inno_ok
:inno_install
echo      Inno Setup not found - installing it now (needs admin once)...
winget install --id JRSoftware.InnoSetup -e --silent --accept-package-agreements --accept-source-agreements
:: re-check both locations after install
if exist "%INNO_DIR%\ISCC.exe" goto :inno_ok
if exist "%INNO_DIR2%\ISCC.exe" set "INNO_DIR=%INNO_DIR2%" & goto :inno_ok
where ISCC.exe >nul 2>&1
if %errorlevel%==0 goto :inno_ok
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
