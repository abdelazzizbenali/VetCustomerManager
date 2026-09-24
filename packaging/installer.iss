; =========================================================================
;  VetCustomerManager - Inno Setup installer
;  Behaviour contract (what the doctor wanted, exactly):
;    * closing the app closes EVERYTHING (handled by the app itself)
;    * installing = FRESH: any running copy is killed, the old install
;      folder is wiped, then the new files land
;    * uninstalling removes EVERYTHING: program files AND the per-user
;      data folder (%APPDATA%\VetCustomerManager)
; =========================================================================

#define MyAppName "VetCustomerManager"
#define MyAppVersion "4.0.0"
#define MyAppPublisher "AAB"

[Setup]
AppId={{7F3A9C24-5E1B-4D6F-9A2C-8D4B6E1F3A5C}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={userpf}\VetCustomerManager
DefaultGroupName={#MyAppName}
PrivilegesRequired=lowest
OutputDir=..\dist\installer
OutputBaseFilename=VetCustomerManager-Setup-{#MyAppVersion}
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\VetCustomerManager.exe
Compression=lzma2/ultra64
SolidCompression=yes
WizardStyle=modern
DisableProgramGroupPage=yes
CloseApplications=yes
RestartApplications=no
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional icons:"

[Files]
Source: "..\dist\app-image\VetCustomerManager\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\VetCustomerManager.exe"
Name: "{userdesktop}\{#MyAppName}"; Filename: "{app}\VetCustomerManager.exe"; Tasks: desktopicon

[InstallDelete]
; nothing from a previous install may survive
Type: filesandordirs; Name: "{app}"

[UninstallDelete]
; uninstall = total removal: program + user data
Type: filesandordirs; Name: "{app}"
Type: filesandordirs; Name: "{userappdata}\VetCustomerManager"

[Run]
Filename: "{app}\VetCustomerManager.exe"; Description: "Launch {#MyAppName} now"; Flags: nowait postinstall skipifsilent

[Code]
{ No old copy is allowed to be running while we install or uninstall -
  kill both processes up front so file locks can never fail the setup. }
procedure KillOld();
var
  ResultCode: Integer;
begin
  Exec('taskkill.exe', '/IM VetCustomerManager.exe /T /F', '', SW_HIDE,
    ewWaitUntilTerminated, ResultCode);
end;

function InitializeSetup(): Boolean;
begin
  KillOld();
  Result := True;
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usUninstall then
    KillOld();
end;
