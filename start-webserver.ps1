<#
.SYNOPSIS
    Startet das AresStack-Webserver-Jar mit einer frei waehlbaren Java-Installation.

.DESCRIPTION
    Sucht installierte JREs/JDKs (PATH, JAVA_HOME, uebliche Installationsordner),
    laesst die gewuenschte Version auswaehlen und startet damit das Server-Jar aus
    dem Ordner dieses Skripts (java -jar). Der Webserver benoetigt Java 21+;
    aeltere Versionen werden markiert.

.PARAMETER Jar
    Pfad zum Jar. Ohne Angabe wird im Skriptordner gesucht (bevorzugt *-all.jar).

.PARAMETER JavaHome
    Ueberspringt die Auswahl und nutzt direkt diese Java-Installation
    (Ordner mit bin\java.exe oder direkt der Pfad zu java.exe).

.PARAMETER Root
    Wurzelverzeichnis der Installation (enthaelt config\webserver.json, data\,
    generated\, logs\, bin\caddy.exe). Ohne Angabe der Ordner des Jars - genau
    wie bei den generierten Startskripten (APP_HOME). Wird als
    -Dwebserver.root uebergeben, damit das Arbeitsverzeichnis egal ist.

.PARAMETER JvmArgs
    Zusaetzliche JVM-Argumente, z.B. -JvmArgs '-Xmx1g'

.PARAMETER AppArgs
    Argumente fuer die Anwendung (nach dem Jar-Namen).

.EXAMPLE
    .\start-webserver.ps1
.EXAMPLE
    .\start-webserver.ps1 -JavaHome 'C:\Program Files\Zulu\zulu-21' -JvmArgs '-Xmx2g'
#>

[CmdletBinding()]
param(
    [string]   $Jar,
    [string]   $Root,
    [string]   $JavaHome,
    [string[]] $JvmArgs = @(),
    [string[]] $AppArgs = @()
)

$ErrorActionPreference = 'Stop'
$MinJavaVersion = 21

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

function Resolve-JavaExe {
    param([string] $Path)

    if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
    if ($Path -like '*java.exe' -and (Test-Path -LiteralPath $Path)) { return (Resolve-Path -LiteralPath $Path).Path }

    $candidate = Join-Path $Path 'bin\java.exe'
    if (Test-Path -LiteralPath $candidate) { return (Resolve-Path -LiteralPath $candidate).Path }
    return $null
}

function Get-JavaInfo {
    param([string] $Exe)

    # 'java -version' schreibt auf stderr; 2>&1 fasst beide Stroeme zusammen.
    # ErrorActionPreference muss dabei lokal gelockert werden, sonst wertet
    # PowerShell die stderr-Zeilen als abbrechenden Fehler.
    $ErrorActionPreference = 'Continue'
    try { $output = & $Exe -version 2>&1 | Out-String } catch { return $null }
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($output)) { return $null }

    $versionString = 'unbekannt'
    $major = 0
    if ($output -match '"([0-9][0-9._\-a-zA-Z+]*)"') {
        $versionString = $Matches[1]
        # 1.8.0_481 -> 8, ansonsten die fuehrende Zahl (21.0.5 -> 21)
        if ($versionString -match '^1\.([0-9]+)') { $major = [int]$Matches[1] }
        elseif ($versionString -match '^([0-9]+)') { $major = [int]$Matches[1] }
    }

    $vendor = ($output -split "`n" | Select-Object -First 1).Trim()

    [pscustomobject]@{
        Path    = $Exe
        Version = $versionString
        Major   = $major
        Vendor  = $vendor
    }
}

function Find-JavaInstallations {
    $exes = New-Object System.Collections.Generic.List[string]

    # 1) JAVA_HOME
    $fromHome = Resolve-JavaExe $env:JAVA_HOME
    if ($fromHome) { $exes.Add($fromHome) }

    # 2) Alles, was ueber den PATH erreichbar ist
    foreach ($cmd in (Get-Command java.exe -All -ErrorAction SilentlyContinue)) {
        if ($cmd.Source) { $exes.Add($cmd.Source) }
    }

    # 3) Uebliche Installationsverzeichnisse
    $roots = @(
        "$env:ProgramFiles\Java",
        "$env:ProgramFiles\Zulu",
        "$env:ProgramFiles\Eclipse Adoptium",
        "$env:ProgramFiles\Microsoft",
        "$env:ProgramFiles\Amazon Corretto",
        "$env:ProgramFiles\BellSoft",
        "$env:ProgramFiles\RedHat",
        "$env:ProgramFiles\SapMachine",
        "$env:ProgramFiles\JetBrains",
        "${env:ProgramFiles(x86)}\Java",
        "$env:LOCALAPPDATA\Programs\Eclipse Adoptium",
        "$env:USERPROFILE\.jdks",
        "$env:USERPROFILE\scoop\apps"
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_) }

    foreach ($root in $roots) {
        Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            $exe = Resolve-JavaExe $_.FullName
            if ($exe) { $exes.Add($exe) }
            # Eine Ebene tiefer (z.B. scoop\apps\temurin21\current)
            Get-ChildItem -LiteralPath $_.FullName -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $nested = Resolve-JavaExe $_.FullName
                if ($nested) { $exes.Add($nested) }
            }
        }
    }

    $seen = @{}
    $result = foreach ($exe in $exes) {
        $key = $exe.ToLowerInvariant()
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true

        # java.exe im Oracle-"javapath"-Ordner ist nur ein Symlink auf eine andere
        # Installation - trotzdem anzeigen, da es die PATH-Standardversion ist.
        $info = Get-JavaInfo $exe
        if ($info) { $info }
    }

    $result | Sort-Object -Property @{ Expression = 'Major'; Descending = $true }, Path
}

function Select-Jar {
    if ($Jar) {
        if (-not (Test-Path -LiteralPath $Jar)) { throw "Jar nicht gefunden: $Jar" }
        return (Resolve-Path -LiteralPath $Jar).Path
    }

    $jars = @(Get-ChildItem -LiteralPath $scriptDir -Filter '*.jar' -File -ErrorAction SilentlyContinue)
    if ($jars.Count -eq 0) { throw "Kein *.jar im Ordner '$scriptDir' gefunden. Alternativ mit -Jar <pfad> starten." }
    if ($jars.Count -eq 1) { return $jars[0].FullName }

    # Fat Jar bevorzugen, falls eindeutig
    $fat = @($jars | Where-Object { $_.Name -like '*-all.jar' })
    if ($fat.Count -eq 1) { return $fat[0].FullName }

    Write-Host ''
    Write-Host 'Mehrere Jars gefunden:' -ForegroundColor Cyan
    for ($i = 0; $i -lt $jars.Count; $i++) {
        Write-Host ('  [{0}] {1}' -f ($i + 1), $jars[$i].Name)
    }
    while ($true) {
        $answer = Read-Host 'Jar auswaehlen (Nummer)'
        $index = 0
        if ([int]::TryParse($answer, [ref]$index) -and $index -ge 1 -and $index -le $jars.Count) {
            return $jars[$index - 1].FullName
        }
        Write-Host 'Ungueltige Eingabe.' -ForegroundColor Yellow
    }
}

function Select-Java {
    param([object[]] $Installations)

    Write-Host ''
    Write-Host 'Gefundene Java-Installationen:' -ForegroundColor Cyan
    for ($i = 0; $i -lt $Installations.Count; $i++) {
        $it = $Installations[$i]
        $flag = if ($it.Major -lt $MinJavaVersion) { "  (zu alt, benoetigt Java $MinJavaVersion+)" } else { '' }
        $color = if ($it.Major -lt $MinJavaVersion) { 'DarkGray' } else { 'Gray' }
        Write-Host ('  [{0,2}] Java {1,-12} {2}{3}' -f ($i + 1), $it.Version, $it.Path, $flag) -ForegroundColor $color
    }

    $default = ($Installations | Where-Object { $_.Major -ge $MinJavaVersion } | Select-Object -First 1)
    $defaultIndex = if ($default) { [array]::IndexOf($Installations, $default) + 1 } else { 1 }

    while ($true) {
        $answer = Read-Host "Java auswaehlen (Nummer, Enter = $defaultIndex)"
        if ([string]::IsNullOrWhiteSpace($answer)) { return $Installations[$defaultIndex - 1] }
        $index = 0
        if ([int]::TryParse($answer, [ref]$index) -and $index -ge 1 -and $index -le $Installations.Count) {
            return $Installations[$index - 1]
        }
        Write-Host 'Ungueltige Eingabe.' -ForegroundColor Yellow
    }
}

# --- Ablauf -----------------------------------------------------------------

$jarPath = Select-Jar

if ($JavaHome) {
    $exe = Resolve-JavaExe $JavaHome
    if (-not $exe) { throw "Keine java.exe unter '$JavaHome' gefunden." }
    $java = Get-JavaInfo $exe
    if (-not $java) { throw "'$exe' liess sich nicht ausfuehren." }
} else {
    Write-Host 'Suche Java-Installationen ...' -ForegroundColor DarkGray
    $installations = @(Find-JavaInstallations)
    if ($installations.Count -eq 0) { throw 'Keine Java-Installation gefunden. Bitte Java 21+ installieren.' }
    $java = Select-Java $installations
}

if ($java.Major -lt $MinJavaVersion) {
    Write-Host ''
    Write-Host ("Achtung: Java {0} ist aelter als die benoetigte Version {1}. Der Start schlaegt vermutlich fehl." -f $java.Version, $MinJavaVersion) -ForegroundColor Yellow
    $go = Read-Host 'Trotzdem starten? [j/N]'
    if ($go -notmatch '^(j|y)') { return }
}

# Ohne -Dwebserver.root nimmt die Anwendung das Arbeitsverzeichnis als
# Installationswurzel und legt dort eine leere Konfiguration an.
if ([string]::IsNullOrWhiteSpace($Root)) { $Root = Split-Path -Parent $jarPath }
if (-not (Test-Path -LiteralPath $Root)) { throw "Wurzelverzeichnis nicht gefunden: $Root" }
$rootPath = (Resolve-Path -LiteralPath $Root).Path

$configFile = Join-Path $rootPath 'config\webserver.json'

$arguments = @()
$arguments += "-Dwebserver.root=$rootPath"
$arguments += $JvmArgs
$arguments += @('-jar', $jarPath)
$arguments += $AppArgs

Write-Host ''
Write-Host ("Java   : {0} ({1})" -f $java.Version, $java.Path) -ForegroundColor Green
Write-Host ("Jar    : {0}" -f $jarPath) -ForegroundColor Green
Write-Host ("Root   : {0}" -f $rootPath) -ForegroundColor Green
if (Test-Path -LiteralPath $configFile) {
    Write-Host ("Config : {0}" -f $configFile) -ForegroundColor Green
} else {
    Write-Host ("Config : {0} (noch nicht vorhanden - wird neu angelegt)" -f $configFile) -ForegroundColor Yellow
}
Write-Host ''

& $java.Path @arguments
exit $LASTEXITCODE
