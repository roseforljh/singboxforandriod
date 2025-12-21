# Final Fix Build Script
# 1. Downloads/Uses Go 1.23 (Fixed path to avoid re-download)
# 2. Builds with correct package name (Fixes crash)
# 3. Builds with aggressive strip (Fixes size)

param(
    [string]$Version = "1.10.7",
    [string]$OutputDir = "$PSScriptRoot\..\app\libs"
)

$ErrorActionPreference = "Stop"
$CacheDir = Join-Path $env:TEMP "SingBoxBuildCache_Fixed"
$GoZipPath = Join-Path $CacheDir "go1.23.4.zip"
$GoExtractPath = Join-Path $CacheDir "go_extract"
$GoRoot = Join-Path $GoExtractPath "go"
$GoBin = Join-Path $GoRoot "bin"

Write-Host "[1/6] Setting up workspace..." -ForegroundColor Yellow
if (-not (Test-Path $CacheDir)) { New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null }

# 1. Check/Download Go 1.23
if (-not (Test-Path "$GoBin\go.exe")) {
    if (-not (Test-Path $GoZipPath)) {
        Write-Host "[2/6] Downloading Go 1.23.4 (Required for build compatibility)..." -ForegroundColor Yellow
        try {
            Invoke-WebRequest -Uri "https://go.dev/dl/go1.23.4.windows-amd64.zip" -OutFile $GoZipPath
        } catch {
            Write-Host "Download failed." -ForegroundColor Red
            exit 1
        }
    } else {
        Write-Host "[2/6] Found cached Go zip..." -ForegroundColor Green
    }
    
    Write-Host "Extracting Go..." -ForegroundColor Yellow
    if (Test-Path $GoExtractPath) { Remove-Item -Recurse -Force $GoExtractPath }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($GoZipPath, $GoExtractPath)
} else {
    Write-Host "[2/6] Using cached Go 1.23.4 environment..." -ForegroundColor Green
}

# 2. Setup Env
Write-Host "[3/6] Configuring Environment..." -ForegroundColor Yellow
$env:GOROOT = $GoRoot
$env:PATH = "$GoBin;$env:PATH"
$env:GOPATH = Join-Path $CacheDir "gopath"
$env:PATH = "$env:PATH;$env:GOPATH\bin"

# 3. Install Tools
Write-Host "[4/6] Installing build tools..." -ForegroundColor Yellow
# Ensure bind source is present
go get golang.org/x/mobile/bind
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

# 4. Clone/Update Source
Write-Host "[5/6] Preparing Source..." -ForegroundColor Yellow
$BuildDir = Join-Path $CacheDir "singbox-source"
if (-not (Test-Path $BuildDir)) {
    git clone --depth 1 --branch "v$Version" https://github.com/SagerNet/sing-box.git $BuildDir
}
Push-Location $BuildDir

# Fix deps in source
# Create a dummy file to force retention of mobile/bind dependency
$DummyFile = Join-Path $BuildDir "tools_build.go"
# Must match existing package name in root (box)
Set-Content -Path $DummyFile -Value 'package box; import _ "golang.org/x/mobile/bind"'

go get golang.org/x/mobile/bind
go mod tidy

# 5. Build
$BUILD_TAGS = "with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api"
Write-Host "Building optimized kernel (Package: io.nekohasekai.libbox)..." -ForegroundColor Yellow

# IMPORTANT: -javapkg should be the prefix. Gomobile appends the go package name 'libbox'.
# So io.nekohasekai -> io.nekohasekai.libbox
gomobile bind -v -androidapi 21 -target "android/arm64" -tags "$BUILD_TAGS" -javapkg io.nekohasekai -trimpath -ldflags "-s -w -buildid= -extldflags '-Wl,-s'" -o "libbox.aar" ./experimental/libbox

if ($LASTEXITCODE -eq 0) {
    Write-Host "[6/6] Build Success! Updating project..." -ForegroundColor Green
    $Dest = $OutputDir
    if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Force -Path $Dest | Out-Null }
    Copy-Item "libbox.aar" (Join-Path $Dest "libbox.aar") -Force
    Write-Host "Updated libbox.aar at $Dest" -ForegroundColor Cyan
} else {
    Write-Host "Build failed." -ForegroundColor Red
}

Pop-Location