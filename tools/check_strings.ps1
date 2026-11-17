# Tìm chuỗi giao diện viết cứng trong code Compose (Stage 8 E3). Lint `HardcodedText` chỉ bắt chuỗi trong layout XML,
# không thấy `Text("…")` của Compose, nên cần thêm bước này. Thoát mã 1 khi còn chỗ nào.
#
#   pwsh .\tools\check_strings.ps1
#
# Bỏ qua một dòng có chủ ý (vd chuỗi kỹ thuật không dịch): thêm ghi chú `// strings-ok` cuối dòng.
param(
    [string[]]$Roots = @('app/src/main/kotlin', 'app/src/debug/kotlin', 'feature')
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$repo = Split-Path -Parent $PSScriptRoot

# Tệp không phải giao diện: chữ ở đây là nội dung bản chép lời giả của Gemini giả (bản debug), không hiện lên nút / nhãn.
$skipFiles = @('FakeTranscriber.kt')

# Chuỗi literal có ít nhất một chữ cái, đặt vào chỗ hiện lên giao diện.
$patterns = @(
    'Text\(\s*"[^"]*\p{L}',
    '\btext\s*=\s*"[^"]*\p{L}',
    '\blabel\s*=\s*"[^"]*\p{L}',
    '\btitle\s*=\s*"[^"]*\p{L}',
    'contentDescription\s*=\s*"[^"]*\p{L}',
    'Toast\.makeText\([^,]+,\s*"[^"]*\p{L}',
    'setContentTitle\(\s*"[^"]*\p{L}',
    'setContentText\(\s*"[^"]*\p{L}'
)

$hits = @()
foreach ($root in $Roots) {
    $dir = Join-Path $repo $root
    if (-not (Test-Path $dir)) { continue }
    Get-ChildItem $dir -Recurse -Filter *.kt | Where-Object {
        $_.FullName -match '[\\/]src[\\/](main|debug)[\\/]' -and $_.Name -notin $skipFiles
    } | ForEach-Object {
        $file = $_
        $n = 0
        foreach ($line in [IO.File]::ReadAllLines($file.FullName)) {
            $n++
            if ($line -match '//\s*strings-ok') { continue }
            if ($line.TrimStart().StartsWith('//') -or $line.TrimStart().StartsWith('*')) { continue }
            # Bỏ phần nội suy (`${x}`, `$x`): chuỗi chỉ ghép giá trị, không có chữ viết cứng.
            $clean = $line -replace '\$\{[^}]*\}', '' -replace '\$[A-Za-z_]\w*', ''
            foreach ($p in $patterns) {
                if ($clean -match $p) {
                    $hits += "$([IO.Path]::GetRelativePath($repo, $file.FullName)):${n}: $($line.Trim())"
                    break
                }
            }
        }
    }
}

if ($hits.Count -gt 0) {
    $hits | ForEach-Object { Write-Host $_ }
    Write-Host "$($hits.Count) chỗ có chuỗi giao diện viết cứng: chuyển vào strings.xml (vi + en)."
    exit 1
}
Write-Host 'Không có chuỗi giao diện viết cứng.'
