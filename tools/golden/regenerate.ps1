# Tạo lại toàn bộ dữ liệu golden trong testdata/golden/ (chạy bằng PowerShell 7).
#
# Chạy lần lượt mọi tools/golden/gen_*.py bằng Python của Guzz desktop, dừng ở lỗi đầu tiên.
#   -B: không ghi __pycache__ vào thư mục desktop
#   -s: chỉ dùng thư viện của runtime, bỏ site-packages của tài khoản Windows
# Thư mục desktop: biến môi trường GUZZ_DESKTOP_DIR, mặc định D:\Guzz.

$ErrorActionPreference = 'Stop'

$desktop = if ($env:GUZZ_DESKTOP_DIR) { $env:GUZZ_DESKTOP_DIR } else { 'D:\Guzz' }
$python = Join-Path $desktop 'runtime\python\python.exe'
if (-not (Test-Path $python)) {
    throw "Không thấy $python. Đặt GUZZ_DESKTOP_DIR trỏ tới thư mục Guzz desktop."
}

$scripts = Get-ChildItem -Path $PSScriptRoot -Filter 'gen_*.py' | Sort-Object Name
foreach ($script in $scripts) {
    Write-Host "== $($script.Name)"
    & $python -B -s $script.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "$($script.Name) lỗi (mã thoát $LASTEXITCODE)"
    }
}
Write-Host "Xong: $($scripts.Count) script."
