#Requires -Version 5.1
# ============================================================
#  UltimateDiceBot Pro — All-In-One Windows Deploy Script
#  deploy.ps1  |  Version 2.0
# ============================================================
#  Usage:
#    .\deploy.ps1                          # auto-detect everything
#    .\deploy.ps1 -JavaHome "C:\Program Files\Java\jdk-21"
#    .\deploy.ps1 -DreamBotJar "D:\games\dreambot\client.jar"
#    .\deploy.ps1 -SourceFile ".\UltimateDiceBot.java"
#    .\deploy.ps1 -SkipCopy                # compile only, no install
# ============================================================

param(
    [string]$JavaHome        = "",
    [string]$DreamBotJar     = "",
    [string]$SourceFile      = "UltimateDiceBot.java",
    [string]$OutputJar       = "UltimateDiceBot.jar",
    [switch]$SkipCopy,
    [switch]$Verbose
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ─────────────────────────────────────────────────────────────────────────────
# COLOUR HELPERS
# ─────────────────────────────────────────────────────────────────────────────
function Write-Step   { param([string]$Msg) Write-Host "[STEP] $Msg"  -ForegroundColor Cyan }
function Write-Ok     { param([string]$Msg) Write-Host "[ OK ] $Msg"  -ForegroundColor Green }
function Write-Warn   { param([string]$Msg) Write-Host "[WARN] $Msg"  -ForegroundColor Yellow }
function Write-Err    { param([string]$Msg) Write-Host "[FAIL] $Msg"  -ForegroundColor Red }
function Write-Info   { param([string]$Msg) Write-Host "[INFO] $Msg"  -ForegroundColor Gray }

# ─────────────────────────────────────────────────────────────────────────────
# BANNER
# ─────────────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host "   UltimateDiceBot Pro v2.0 — AIO Deploy Script (Windows)"    -ForegroundColor Magenta
Write-Host "============================================================" -ForegroundColor Magenta
Write-Host ""

# ─────────────────────────────────────────────────────────────────────────────
# STEP 1 — LOCATE JAVA
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "Locating Java installation..."

$javac = $null
$java  = $null

if ($JavaHome -ne "" -and (Test-Path "$JavaHome\bin\javac.exe")) {
    $javac = "$JavaHome\bin\javac.exe"
    $java  = "$JavaHome\bin\java.exe"
    Write-Ok "Using user-supplied JAVA_HOME: $JavaHome"
}
else {
    # 1. PATH
    $found = Get-Command "javac" -ErrorAction SilentlyContinue
    if ($found) {
        $javac = $found.Source
        $java  = (Get-Command "java" -ErrorAction SilentlyContinue).Source
        Write-Ok "Found javac in PATH: $javac"
    }
    else {
        # 2. JAVA_HOME env var
        if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\javac.exe")) {
            $javac = "$env:JAVA_HOME\bin\javac.exe"
            $java  = "$env:JAVA_HOME\bin\java.exe"
            Write-Ok "Found Java via JAVA_HOME: $env:JAVA_HOME"
        }
        else {
            # 3. Common install directories
            $candidates = @(
                "C:\Program Files\Java",
                "C:\Program Files\Eclipse Adoptium",
                "C:\Program Files\Microsoft",
                "$env:LOCALAPPDATA\Programs\Eclipse Adoptium"
            )
            foreach ($base in $candidates) {
                if (Test-Path $base) {
                    $dirs = Get-ChildItem $base -Directory -ErrorAction SilentlyContinue |
                            Where-Object { $_.Name -match "^jdk" } |
                            Sort-Object Name -Descending
                    foreach ($dir in $dirs) {
                        $candidate = Join-Path $dir.FullName "bin\javac.exe"
                        if (Test-Path $candidate) {
                            $javac = $candidate
                            $java  = Join-Path $dir.FullName "bin\java.exe"
                            Write-Ok "Auto-detected Java: $($dir.FullName)"
                            break
                        }
                    }
                }
                if ($javac) { break }
            }
        }
    }
}

if (-not $javac) {
    Write-Err "Java Development Kit (JDK) not found."
    Write-Host ""
    Write-Host "  Please install JDK 11+ from one of these sources:" -ForegroundColor Yellow
    Write-Host "    https://adoptium.net/               (Eclipse Temurin – recommended)"
    Write-Host "    https://www.oracle.com/java/        (Oracle JDK)"
    Write-Host ""
    Write-Host "  After installing, either:" -ForegroundColor Yellow
    Write-Host "    • Add Java to your PATH and re-run this script"
    Write-Host "    • Or run:  .\deploy.ps1 -JavaHome `"C:\Path\To\JDK`""
    Write-Host ""
    exit 1
}

# Print version
$jvOut = & $java -version 2>&1 | Select-Object -First 1
Write-Info "Java version: $jvOut"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 2 — LOCATE SOURCE FILE
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "Locating source file: $SourceFile"

if (-not (Test-Path $SourceFile)) {
    # Try same directory as this script
    $scriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
    $altSource  = Join-Path $scriptDir $SourceFile
    if (Test-Path $altSource) {
        $SourceFile = $altSource
    }
    else {
        Write-Err "Source file not found: $SourceFile"
        Write-Host "  Place UltimateDiceBot.java in the same folder as this script." -ForegroundColor Yellow
        exit 1
    }
}
Write-Ok "Source: $(Resolve-Path $SourceFile)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 3 — LOCATE DREAMBOT client.jar
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "Locating DreamBot client.jar..."

if ($DreamBotJar -eq "" -or -not (Test-Path $DreamBotJar)) {
    $candidates = @(
        "$env:USERPROFILE\.dreambot\client.jar",
        "$env:APPDATA\.dreambot\client.jar",
        "C:\DreamBot\client.jar",
        "$env:USERPROFILE\DreamBot\client.jar"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { $DreamBotJar = $c; break }
    }

    if ($DreamBotJar -eq "" -or -not (Test-Path $DreamBotJar)) {
        Write-Err "DreamBot client.jar not found."
        Write-Host ""
        Write-Host "  Download DreamBot from https://dreambot.org/ and launch it once" -ForegroundColor Yellow
        Write-Host "  so it downloads client.jar to %USERPROFILE%\.dreambot\client.jar" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "  Or run:  .\deploy.ps1 -DreamBotJar `"C:\path\to\client.jar`""
        Write-Host ""
        exit 1
    }
}
Write-Ok "DreamBot client.jar: $DreamBotJar"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 4 — COMPILE
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "Compiling UltimateDiceBot.java..."

$buildDir = Join-Path $PSScriptRoot "build"
if (-not (Test-Path $buildDir)) { New-Item -ItemType Directory -Path $buildDir | Out-Null }

# Clean previous build
Get-ChildItem $buildDir -Filter "*.class" | Remove-Item -Force -ErrorAction SilentlyContinue

$compileArgs = @(
    "-cp", $DreamBotJar,
    "-d", $buildDir,
    "-source", "11",
    "-target", "11",
    $SourceFile
)

Write-Info "Running: javac $($compileArgs -join ' ')"
$compileOutput = & $javac @compileArgs 2>&1
$compileExit   = $LASTEXITCODE

if ($compileExit -ne 0) {
    Write-Err "Compilation FAILED (exit $compileExit)"
    Write-Host ""
    Write-Host "─── Compiler Output ──────────────────────────────────────────" -ForegroundColor DarkYellow
    $compileOutput | ForEach-Object { Write-Host $_ -ForegroundColor DarkYellow }
    Write-Host "──────────────────────────────────────────────────────────────" -ForegroundColor DarkYellow
    Write-Host ""
    Write-Host "Common fixes:" -ForegroundColor Yellow
    Write-Host "  • Wrong DreamBot API version – try a newer client.jar"
    Write-Host "  • JDK too old – use JDK 11+"
    Write-Host "  • Source file path contains spaces – wrap in quotes"
    exit 1
}

if ($Verbose) { $compileOutput | ForEach-Object { Write-Info $_ } }
$classCount = (Get-ChildItem $buildDir -Filter "*.class" -Recurse).Count
Write-Ok "Compiled successfully ($classCount class files)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 5 — PACKAGE INTO JAR
# ─────────────────────────────────────────────────────────────────────────────
Write-Step "Packaging into $OutputJar..."

$manifestFile = Join-Path $buildDir "MANIFEST.MF"
@"
Manifest-Version: 1.0
Main-Class: UltimateDiceBot
"@ | Set-Content $manifestFile -Encoding UTF8

$jarPath = Join-Path $PSScriptRoot $OutputJar
if (Test-Path $jarPath) { Remove-Item $jarPath -Force }

$jarArgs = @(
    "cf",
    $jarPath,
    "-C", $buildDir, "."
)

# Use jar tool from same JDK
$jarTool = Join-Path (Split-Path $javac -Parent) "jar.exe"
if (-not (Test-Path $jarTool)) { $jarTool = "jar" }

$jarOutput = & $jarTool @jarArgs 2>&1
$jarExit   = $LASTEXITCODE

if ($jarExit -ne 0) {
    Write-Err "JAR packaging FAILED (exit $jarExit)"
    $jarOutput | ForEach-Object { Write-Host $_ -ForegroundColor Red }
    exit 1
}

$jarSize = [math]::Round((Get-Item $jarPath).Length / 1KB, 1)
Write-Ok "JAR created: $jarPath  ($jarSize KB)"

# ─────────────────────────────────────────────────────────────────────────────
# STEP 6 — COPY TO DREAMBOT SCRIPTS FOLDER
# ─────────────────────────────────────────────────────────────────────────────
if (-not $SkipCopy) {
    Write-Step "Installing to DreamBot scripts folder..."

    $scriptsFolders = @(
        "$env:USERPROFILE\.dreambot\scripts",
        "$env:APPDATA\.dreambot\scripts"
    )

    $installed = $false
    foreach ($folder in $scriptsFolders) {
        if (Test-Path (Split-Path $folder -Parent)) {
            if (-not (Test-Path $folder)) {
                New-Item -ItemType Directory -Path $folder -Force | Out-Null
                Write-Info "Created scripts folder: $folder"
            }
            $dest = Join-Path $folder $OutputJar
            Copy-Item $jarPath $dest -Force
            Write-Ok "Installed: $dest"
            $installed = $true
            break
        }
    }

    if (-not $installed) {
        Write-Warn "Could not find DreamBot scripts folder automatically."
        Write-Host "  Manually copy $jarPath to your DreamBot scripts directory." -ForegroundColor Yellow
        Write-Host "  Default path: %USERPROFILE%\.dreambot\scripts\" -ForegroundColor Yellow
    }
}
else {
    Write-Warn "-SkipCopy specified. JAR NOT installed. Copy manually: $jarPath"
}

# ─────────────────────────────────────────────────────────────────────────────
# SUMMARY
# ─────────────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "============================================================" -ForegroundColor Green
Write-Host "   BUILD SUCCESSFUL — UltimateDiceBot Pro v2.0"               -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Green
Write-Host ""
Write-Host "  Source  : $SourceFile"                    -ForegroundColor White
Write-Host "  JAR     : $jarPath"                       -ForegroundColor White
Write-Host "  Classes : $classCount"                    -ForegroundColor White
Write-Host ""
Write-Host "  Next steps:" -ForegroundColor Cyan
Write-Host "    1. Open DreamBot client"
Write-Host "    2. Click the Folder icon in the script selector"
Write-Host "    3. Refresh the script list — 'UltimateDiceBot Pro' will appear"
Write-Host "    4. Select the script and click Start"
Write-Host "    5. Configure settings in the GUI and click 'Start UltimateDiceBot Pro'"
Write-Host ""
Write-Host "  Troubleshooting:" -ForegroundColor DarkCyan
Write-Host "    - 'Cannot resolve symbol' errors → update to latest DreamBot client"
Write-Host "    - Script not listed            → check scripts folder path above"
Write-Host "    - API changes                  → re-run deploy.ps1 after each DreamBot update"
Write-Host ""
