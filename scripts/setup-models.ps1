# setup-models.ps1 — Download RT-DETRv2 detector models from the latest release.
# Verifies SHA256 against models\CHECKSUMS.txt. Idempotent.
#
# Usage: .\scripts\setup-models.ps1
#        $env:OCEANGUARD_REPO = "owner/repo"; .\scripts\setup-models.ps1
$ErrorActionPreference = "Stop"

$Repo          = if ($env:OCEANGUARD_REPO) { $env:OCEANGUARD_REPO } else { "asferrer/OceanguardAI" }
$ModelsDir     = "android\app\src\main\assets\models"
$ChecksumsFile = "models\CHECKSUMS.txt"
$BaseUrl       = "https://github.com/$Repo/releases/latest/download"

$Models = @(
    "rtdetrv2_detector.tflite",
    "rtdetrv2_detector_int8.tflite"
)

New-Item -ItemType Directory -Force -Path $ModelsDir | Out-Null

foreach ($model in $Models) {
    $dest = Join-Path $ModelsDir $model
    if (Test-Path $dest) {
        Write-Host "[setup] Skipping (exists): $model"
        continue
    }
    $url = "$BaseUrl/$model"
    Write-Host "[setup] Downloading: $model"
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing `
        -Headers @{ "User-Agent" = "oceanguard-setup-models" }
}

if (-not (Test-Path $ChecksumsFile)) {
    Write-Host "[setup] CHECKSUMS.txt not found - skipping integrity check"
    Write-Host "[setup] Models ready. Run: cd android; .\gradlew.bat installDebug"
    exit 0
}

Write-Host "[setup] Verifying SHA256 checksums against $ChecksumsFile..."
$lines = Get-Content $ChecksumsFile
$fail = $false
foreach ($line in $lines) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $tokens   = $line -split '\s+', 2
    $expected = $tokens[0].ToLower()
    $filename = $tokens[1].Trim()
    $filepath = Join-Path $ModelsDir $filename
    if (-not (Test-Path $filepath)) { continue }
    $actual = (Get-FileHash -Path $filepath -Algorithm SHA256).Hash.ToLower()
    if ($actual -eq $expected) {
        Write-Host "[setup] OK: $filename"
    } else {
        Write-Host "[ERROR] Checksum mismatch for $filename"
        Write-Host "  expected: $expected"
        Write-Host "  actual:   $actual"
        Write-Host "  Delete the file and re-run this script."
        $fail = $true
    }
}

if ($fail) { exit 1 }

Write-Host "[setup] Models ready. Run: cd android; .\gradlew.bat installDebug"
