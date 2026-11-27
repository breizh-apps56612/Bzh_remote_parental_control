<#
  Émulateurs téléphone + TV sur le PC (Windows), pour travailler en direct avec Claude.
  Claude les pilote par adb (discussion reliée au PC par l'appli Claude), vous les voyez et pouvez cliquer dedans.

  Utilisation (PowerShell) :
    powershell -ExecutionPolicy Bypass -File tools\pc\emulateurs-pc.ps1            # installe si besoin, démarre, installe les applis du site
    powershell -ExecutionPolicy Bypass -File tools\pc\emulateurs-pc.ps1 -Demarrer  # démarre seulement
    powershell -ExecutionPolicy Bypass -File tools\pc\emulateurs-pc.ps1 -Arreter   # ferme les deux émulateurs

  Ne demande jamais de redémarrer le PC : sans accélération matérielle, les émulateurs tournent simplement plus lentement.
  Téléphone = emulator-5554, TV = emulator-5556. Box joignable sur 127.0.0.1:7363 (et 10.0.2.2:7363 depuis le téléphone).
#>
param([switch]$Demarrer, [switch]$Arreter, [int]$Api = 30)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # téléchargements beaucoup plus rapides sous PowerShell 5.1
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$Site = 'https://telecommande-box.netlify.app'
$Tel = 'Telecommande_Telephone'; $Tv = 'Telecommande_TV'

function Ecrire($t) { Write-Host "» $t" -ForegroundColor Cyan }

# --- SDK Android : Android Studio, variables d'environnement, ou installation dans le dossier utilisateur ---
$Sdk = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk") | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $Sdk) { $Sdk = "$env:LOCALAPPDATA\Android\Sdk"; New-Item -ItemType Directory -Force $Sdk | Out-Null }
$env:ANDROID_HOME = $Sdk; $env:ANDROID_SDK_ROOT = $Sdk
if (-not $env:JAVA_HOME -or -not (Test-Path "$env:JAVA_HOME\bin\java.exe")) {
  $jbr = @("$env:ProgramFiles\Android\Android Studio\jbr", "$env:LOCALAPPDATA\Programs\Android Studio\jbr", "$env:LOCALAPPDATA\telecommande-jdk17") |
    Where-Object { Test-Path "$_\bin\java.exe" } | Select-Object -First 1
  if ($jbr) { $env:JAVA_HOME = $jbr } else { $env:JAVA_HOME = $null }
}
if (-not $env:JAVA_HOME -and -not $Arreter -and -not $Demarrer) {
  # Java 17 portable (Eclipse Temurin), dans le dossier utilisateur : sans droits administrateur, sans redémarrage
  Write-Host "» Téléchargement de Java 17 (portable, ~190 Mo)…" -ForegroundColor Cyan
  $a = (Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?os=windows&architecture=x64&image_type=jdk')[0].binary.package
  $zip = "$env:TEMP\jdk17.zip"; Invoke-WebRequest $a.link -OutFile $zip
  if ((Get-FileHash $zip -Algorithm SHA256).Hash.ToLower() -ne $a.checksum.ToLower()) { throw "Empreinte de Java inattendue : installation annulée." }
  $tmp = "$env:TEMP\jdk17"; Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue; Expand-Archive $zip $tmp -Force
  $dst = "$env:LOCALAPPDATA\telecommande-jdk17"; Remove-Item $dst -Recurse -Force -ErrorAction SilentlyContinue
  Move-Item (Get-ChildItem $tmp -Directory | Select-Object -First 1).FullName $dst
  $env:JAVA_HOME = $dst
}
if ($env:JAVA_HOME) { $env:Path = "$env:JAVA_HOME\bin;$env:Path" }
$SdkManager = "$Sdk\cmdline-tools\latest\bin\sdkmanager.bat"
$AvdManager = "$Sdk\cmdline-tools\latest\bin\avdmanager.bat"
$Adb = "$Sdk\platform-tools\adb.exe"; $Emu = "$Sdk\emulator\emulator.exe"

function Adb { param([Parameter(ValueFromRemainingArguments)]$a) & $Adb @a 2>$null }

if ($Arreter) {
  Adb -s emulator-5554 emu kill | Out-Null; Adb -s emulator-5556 emu kill | Out-Null
  Ecrire 'Émulateurs fermés.'; return
}

if (-not $Demarrer) {
  if (-not (Test-Path $SdkManager)) {
    Ecrire 'Installation des outils Android (ligne de commande)…'
    $zip = "$env:TEMP\clt.zip"
    Invoke-WebRequest 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip' -OutFile $zip
    Expand-Archive $zip "$env:TEMP\clt" -Force
    New-Item -ItemType Directory -Force "$Sdk\cmdline-tools\latest" | Out-Null
    Copy-Item "$env:TEMP\clt\cmdline-tools\*" "$Sdk\cmdline-tools\latest" -Recurse -Force
  }
  Ecrire "Images Android $Api (téléphone + TV)…"
  (@('y') * 30) | & $SdkManager --licenses | Out-Null
  & $SdkManager 'platform-tools' 'emulator' "system-images;android-$Api;default;x86_64" "system-images;android-$Api;android-tv;x86" | Out-Null
  $avds = & $Emu -list-avds
  if ($avds -notcontains $Tel) { 'no' | & $AvdManager create avd -n $Tel -k "system-images;android-$Api;default;x86_64" -d pixel | Out-Null }
  if ($avds -notcontains $Tv)  { 'no' | & $AvdManager create avd -n $Tv  -k "system-images;android-$Api;android-tv;x86" -d tv_1080p | Out-Null }
}

# --- Accélération matérielle (WHPX de Windows, ou pilote AEHD de Google) : jamais de redémarrage demandé ---
$accel = (& $Emu -accel-check 2>&1) -join ' '
if ($accel -notmatch 'is installed and usable' -and -not $Demarrer) {
  Write-Host "» Installation du pilote d'accélération de Google (AEHD)…" -ForegroundColor Cyan
  & $SdkManager 'extras;google;Android_Emulator_Hypervisor_Driver' | Out-Null
  $aehd = "$Sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat"
  $admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
  if ((Test-Path $aehd) -and $admin) { & cmd /c "`"$aehd`"" | Out-Null }
  elseif (Test-Path $aehd) { Write-Host "Relancez ce script dans un PowerShell ouvert « en tant qu'administrateur » pour installer le pilote d'accélération." -ForegroundColor Yellow }
  $accel = (& $Emu -accel-check 2>&1) -join ' '
}
if ($accel -notmatch 'is installed and usable') {
  Write-Host "Accélération matérielle indisponible : $accel" -ForegroundColor Yellow
  Write-Host "Copiez ce message à Claude. (Aucun redémarrage n'est nécessaire pour la suite.)" -ForegroundColor Yellow
}

# --- Démarrage (fenêtres visibles) ---
Adb start-server | Out-Null
foreach ($e in @(@{n=$Tv; p=5556}, @{n=$Tel; p=5554})) {
  if (-not ((Adb devices) -match "emulator-$($e.p)")) {
    Start-Process $Emu -ArgumentList @('-avd', $e.n, '-port', $e.p, '-no-snapshot-save', '-no-boot-anim', '-netfast') -WindowStyle Normal
  }
}
Ecrire 'Démarrage des émulateurs…'
$fin = (Get-Date).AddMinutes(15)
do {
  Start-Sleep 5
  $a = (Adb -s emulator-5554 shell getprop sys.boot_completed) -join ''; $b = (Adb -s emulator-5556 shell getprop sys.boot_completed) -join ''
} until (($a.Trim() -eq '1' -and $b.Trim() -eq '1') -or (Get-Date) -gt $fin)
if (-not ($a.Trim() -eq '1' -and $b.Trim() -eq '1')) { throw "Les émulateurs n'ont pas démarré à temps (téléphone=$a TV=$b)." }
Ecrire 'Émulateurs prêts.'

# --- Applis officielles du site, vérifiées par leur empreinte SHA-256 ---
if (-not $Demarrer) {
  $dir = "$env:TEMP\telecommande-apk"; New-Item -ItemType Directory -Force $dir | Out-Null
  $v = Invoke-RestMethod "$Site/version.json?t=$([DateTimeOffset]::Now.ToUnixTimeSeconds())"
  foreach ($x in @(@{f='Telecommande.apk'; s=$v.phone.sha256; d='emulator-5554'}, @{f='TelecommandeBox.apk'; s=$v.box.sha256; d='emulator-5556'})) {
    Invoke-WebRequest "$Site/$($x.f)" -OutFile "$dir\$($x.f)"
    $h = (Get-FileHash "$dir\$($x.f)" -Algorithm SHA256).Hash.ToLower()
    if ($x.s -and $h -ne $x.s.ToLower()) { throw "Empreinte inattendue pour $($x.f) : installation annulée." }
    Adb -s $x.d install -r -g "$dir\$($x.f)" | Out-Null
  }
  # Protection Box activée comme le ferait le parent
  Adb -s emulator-5556 shell settings put secure enabled_accessibility_services fr.paul.telecommande.box/.GuardService | Out-Null
  Adb -s emulator-5556 shell settings put secure accessibility_enabled 1 | Out-Null
  Adb -s emulator-5556 shell appops set fr.paul.telecommande.box GET_USAGE_STATS allow | Out-Null
  Ecrire "Applis installées : téléphone $($v.phone.versionName), TV $($v.box.versionName)."
}
Adb -s emulator-5556 forward tcp:7363 tcp:7363 | Out-Null
Adb -s emulator-5556 emu redir add tcp:7364:7363 | Out-Null
Adb -s emulator-5556 shell monkey -p fr.paul.telecommande.box -c android.intent.category.LEANBACK_LAUNCHER 1 | Out-Null
Adb -s emulator-5554 shell monkey -p fr.paul.telecommande -c android.intent.category.LAUNCHER 1 | Out-Null
Ecrire 'Prêt : téléphone = emulator-5554, TV = emulator-5556.'
