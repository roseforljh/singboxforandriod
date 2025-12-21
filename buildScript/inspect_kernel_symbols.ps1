# Script to inspect JNI symbols in libbox.so

# Use PSScriptRoot to locate the AAR correctly
$AarPath = Join-Path (Resolve-Path "$PSScriptRoot\..\app\libs") "libbox.aar"
$WorkDir = Join-Path $env:TEMP "symbol_inspect_$(Get-Random)"
$AarZip = Join-Path $WorkDir "libbox.zip"
$ExtractDir = Join-Path $WorkDir "extract"

# Find NDK nm tool
$NdkDir = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\ndk" -Directory | Sort-Object Name -Descending | Select-Object -First 1
$NmTool = Get-ChildItem $NdkDir.FullName -Recurse -Filter "llvm-nm.exe" | Select-Object -First 1
if (-not $NmTool) {
    Write-Host "Error: llvm-nm.exe not found in NDK" -ForegroundColor Red
    exit 1
}
Write-Host "Using nm tool: $($NmTool.FullName)" -ForegroundColor Cyan

# Extract .so
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
Copy-Item $AarPath $AarZip
Expand-Archive $AarZip -DestinationPath $ExtractDir -Force
$SoPath = Join-Path $ExtractDir "jni\arm64-v8a\libbox.so"

if (-not (Test-Path $SoPath)) {
    Write-Host "Error: libbox.so not found in AAR" -ForegroundColor Red
    exit 1
}

# Dump symbols
Write-Host "Dumping JNI symbols..." -ForegroundColor Yellow
& $NmTool.FullName -D $SoPath | Select-String "Java_"

# Cleanup
Remove-Item -Recurse -Force $WorkDir