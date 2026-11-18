<#
.SYNOPSIS
    Build bản APK release đã ký để phát hành trên GitHub Releases (Stage 9 D5, D122). Chạy bằng pwsh tại D:\GuzzMobile.

.DESCRIPTION
    Kiểm cây git sạch, đang ở main và trùng origin/main; chạy test + lint; build sideloadRelease với -Pguzz.version;
    kiểm chữ ký (từ chối bản ký bằng khoá debug) và số phiên bản; chép ra dist\guzz-<Version>.apk; in SHA-256 của APK và
    vân tay chứng chỉ. Không tạo tag, không tạo Release: việc đó làm tay sau khi kiểm APK (Stage 9 G4).

    Khoá ký đọc từ biến môi trường (D129, D137), đặt trong chính terminal chạy script, không ghi vào tệp nào:
        $env:GUZZ_KEYSTORE = 'D:\GuzzMobileData\keys\guzz-release.p12'
        $env:GUZZ_KEY_ALIAS = '<alias>'
        $env:GUZZ_KEYSTORE_PASSWORD = Read-Host 'Mật khẩu keystore' -MaskInput
        # GUZZ_KEY_PASSWORD: chỉ cần khi mật khẩu của khoá khác mật khẩu keystore
    Xong thì xoá biến mật khẩu: Remove-Item Env:GUZZ_KEYSTORE_PASSWORD, Env:GUZZ_KEY_PASSWORD -ErrorAction Ignore

.EXAMPLE
    pwsh .\tools\release.ps1 -Version 1.0.0
#>
param(
    [Parameter(Mandatory)][string]$Version,
    # Chỉ để thử script: bỏ kiểm git (cây bẩn, khác origin/main). Không dùng khi phát hành thật.
    [switch]$SkipGitCheck
)

$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)

function Fail([string]$msg) {
    Write-Host "LỖI: $msg" -ForegroundColor Red
    exit 1
}

if ($Version -notmatch '^[1-9]\d?\.\d{1,2}\.\d{1,2}$') { Fail "Version '$Version' phải dạng MAJOR.MINOR.PATCH (D130), vd 1.0.0." }

# 1. Cây git: bản phát hành phải dựng lại được đúng từ một commit đã push.
if (-not $SkipGitCheck) {
    if (git status --porcelain) { Fail 'Cây git chưa sạch: commit hoặc bỏ thay đổi trước.' }
    $branch = git rev-parse --abbrev-ref HEAD
    if ($branch -ne 'main') { Fail "Đang ở nhánh '$branch', cần main." }
    git fetch origin main --quiet
    if ($LASTEXITCODE -ne 0) { Fail 'Không fetch được origin/main.' }
    if ((git rev-parse HEAD) -ne (git rev-parse origin/main)) { Fail 'main khác origin/main: push (hoặc pull) trước.' }
}

# 2. Khoá ký.
foreach ($name in 'GUZZ_KEYSTORE', 'GUZZ_KEYSTORE_PASSWORD', 'GUZZ_KEY_ALIAS') {
    if (-not [Environment]::GetEnvironmentVariable($name)) { Fail "Chưa đặt biến $name (xem phần đầu script)." }
}
if (-not (Test-Path -LiteralPath $env:GUZZ_KEYSTORE)) { Fail "Không thấy keystore: $env:GUZZ_KEYSTORE" }

# 3. Test, lint, build.
& .\gradlew test lint assembleSideloadRelease "-Pguzz.version=$Version" --console=plain
if ($LASTEXITCODE -ne 0) { Fail 'Gradle lỗi (test, lint hoặc build).' }
$apk = 'app\build\outputs\apk\sideload\release\app-sideload-release.apk'
if (-not (Test-Path $apk)) { Fail "Không thấy $apk" }

# 4. Chữ ký và số phiên bản.
$buildTools = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$certs = & $apksigner verify --print-certs $apk 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { Fail "apksigner verify lỗi:`n$certs" }
if ($certs -match 'CN=Android Debug') { Fail 'APK ký bằng khoá debug: kiểm lại GUZZ_KEYSTORE.' }
$apkanalyzer = Join-Path $env:ANDROID_HOME 'cmdline-tools\latest\bin\apkanalyzer.bat'
$versionName = (& $apkanalyzer manifest version-name $apk).Trim()
if ($versionName -ne $Version) { Fail "versionName trong APK là '$versionName', không phải '$Version'." }
$versionCode = (& $apkanalyzer manifest version-code $apk).Trim()

# 5. Chép ra dist\ (không commit, .gitignore) và in thông tin để ghi vào ghi chú phát hành.
New-Item -ItemType Directory -Force dist | Out-Null
$out = "dist\guzz-$Version.apk"
Copy-Item $apk $out -Force
$sha = (Get-FileHash $out -Algorithm SHA256).Hash.ToLower()
$size = [math]::Round((Get-Item $out).Length / 1MB, 1)
$fingerprint = ($certs -split "`n" | Where-Object { $_ -match 'SHA-256 digest' } | Select-Object -First 1).Trim()

Write-Host ''
Write-Host "Xong: $out ($size MB), versionName $versionName, versionCode $versionCode" -ForegroundColor Green
Write-Host "SHA-256 của APK: $sha"
Write-Host "Chứng chỉ: $fingerprint"
