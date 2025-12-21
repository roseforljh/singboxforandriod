# Script to extract optimized libbox.so from NekoBox APK and inject into local AAR
# This solves the build size issue by using NekoBox's pre-compiled, highly optimized kernel.

param(
    [string]$NekoBoxApkUrl = "https://github.com/MatsuriDayo/NekoBoxForAndroid/releases/download/1.3.3/NB4A-1.3.3-arm64-v8a.apk",
    [string]$InputAar = "$PSScriptRoot\..\app\libs\libbox.aar",
    [string]$OutputAar = "$PSScriptRoot\..\app\libs\libbox-nekobox.aar"
)

$ErrorActionPreference = "Stop"
$WorkDir = Join-Path $env:TEMP "nekobox_extract_$(Get-Random)"
$ApkPath = Join-Path $WorkDir "nekobox.apk"
$ApkZipPath = Join-Path $WorkDir "nekobox.zip"
$AarZipPath = Join-Path $WorkDir "libbox_input.zip"
$AarExtractDir = Join-Path $WorkDir "aar_extract"
$ApkExtractDir = Join-Path $WorkDir "apk_extract"

Write-Host "[1/5] Setting up workspace..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
New-Item -ItemType Directory -Force -Path $AarExtractDir | Out-Null
New-Item -ItemType Directory -Force -Path $ApkExtractDir | Out-Null

Write-Host "[2/5] Downloading NekoBox APK..." -ForegroundColor Yellow
try {
    Invoke-WebRequest -Uri $NekoBoxApkUrl -OutFile $ApkPath
} catch {
    Write-Host "Download failed. Please manually download NB4A-arm64-v8a.apk and place it at $ApkPath" -ForegroundColor Red
    exit 1
}

Write-Host "[3/5] Extracting files..." -ForegroundColor Yellow
# Rename files to .zip to satisfy Expand-Archive requirements
Copy-Item -Path $InputAar -Destination $AarZipPath
Copy-Item -Path $ApkPath -Destination $ApkZipPath

# Extract local AAR
Expand-Archive -Path $AarZipPath -DestinationPath $AarExtractDir -Force

# Extract NekoBox APK
Expand-Archive -Path $ApkZipPath -DestinationPath $ApkExtractDir -Force

$soDest = Join-Path $AarExtractDir "jni\arm64-v8a\libbox.so"

# Search for the .so file recursively
$foundSo = Get-ChildItem -Path $ApkExtractDir -Recurse -Filter "libbox.so" | Select-Object -First 1
if (-not $foundSo) {
    # Try finding libgojni.so as fallback
    $foundSo = Get-ChildItem -Path $ApkExtractDir -Recurse -Filter "libgojni.so" | Select-Object -First 1
}

if (-not $foundSo) {
    Write-Host "Error: libbox.so or libgojni.so not found in APK content. Listing files:" -ForegroundColor Red
    Get-ChildItem -Path $ApkExtractDir -Recurse | Select-Object FullName
    exit 1
}

$soSource = $foundSo.FullName
Write-Host "Found kernel at: $soSource" -ForegroundColor Green

Write-Host "[4/5] Injecting NekoBox kernel..." -ForegroundColor Yellow
# Ensure destination directory exists
New-Item -ItemType Directory -Force -Path (Split-Path $soDest) | Out-Null

# Verify sizes
$origSize = if (Test-Path $soDest) { (Get-Item $soDest).Length / 1MB } else { 0 }
$newSize = (Get-Item $soSource).Length / 1MB
Write-Host "  Original .so size: $("{0:N2}" -f $origSize) MB" -ForegroundColor Gray
Write-Host "  NekoBox .so size:  $("{0:N2}" -f $newSize) MB" -ForegroundColor Green

Copy-Item -Path $soSource -Destination $soDest -Force

Write-Host "[5/5] Repackaging AAR..." -ForegroundColor Yellow
$outputAarPath = Resolve-Path $OutputAar -ErrorAction SilentlyContinue
if (-not $outputAarPath) {
    # If file doesn't exist yet, construct path manually to avoid Resolve-Path error
    $outputAarPath = Join-Path (Resolve-Path "$PSScriptRoot\..\app\libs") "libbox-nekobox.aar"
}

# Create zip using .NET API
$sourceDir = $AarExtractDir
$zipFile = $outputAarPath
if (Test-Path $zipFile) { Remove-Item $zipFile }
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::CreateFromDirectory($sourceDir, $zipFile)

Write-Host "Done! Optimized AAR created at: $zipFile" -ForegroundColor Cyan
Write-Host "Action Required: Rename this file to libbox.aar to use it." -ForegroundColor Yellow

# Cleanup
Remove-Item -Recurse -Force $WorkDir