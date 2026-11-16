# ============================================================================
#  Android 固件分区导出工具（仅限自有设备的开发/备份用途）
#
#  前提：设备已 root（Magisk 或内置 su），ADB 可通过 `su` 提权读取块设备。
#        未 root 的设备无法用本脚本（ADB shell 是 uid=2000，读不了 /dev/block）。
#
#  用法：
#    # 1) 只看分区清单和大小（不做任何写入）
#    pwsh -File tools/dump_firmware.ps1 -List
#
#    # 2) 默认导出（引导相关，小、安全）
#    pwsh -File tools/dump_firmware.ps1
#
#    # 3) 导出完整 ROM（含 super 动态分区，约 8.7GB）
#    pwsh -File tools/dump_firmware.ps1 -Full
#
#    # 4) 指定分区
#    pwsh -File tools/dump_firmware.ps1 -Partitions boot_a,dtbo_a,vbmeta_a
#
#    # 5) 连用户数据一起导（含个人隐私，需显式确认）
#    pwsh -File tools/dump_firmware.ps1 -Full -IncludeUserData
#
#  输出：<OutDir>\<分区名>.img + manifest.json + README.txt
# ============================================================================

param(
    [string]$Serial = "",
    [string[]]$Partitions = @(),
    [switch]$Full,
    [switch]$IncludeUserData,
    [switch]$List,
    [string]$OutDir = "D:\project\wuzhanai\dump",
    [string]$Adb = "D:\tools\platform-tools\adb.exe",
    [switch]$KeepOnDevice
)

$ErrorActionPreference = "Stop"

# 引导相关分区（默认导出集）：体积小、是刷机/打补丁最常用的
$DEFAULT_SET = @(
    "boot_a", "boot_b",
    "init_boot_a", "init_boot_b",
    "dtbo_a", "dtbo_b",
    "vbmeta_a", "vbmeta_b",
    "vbmeta_system_a", "vbmeta_system_b",
    "vbmeta_vendor_a", "vbmeta_vendor_b"
)

# 完整 ROM 额外包含的大分区
$FULL_EXTRA = @(
    "super",
    "vendor_boot_a", "vendor_boot_b",
    "recovery_a", "recovery_b",
    "lk_a", "lk_b",
    "md1img_a", "md1img_b",
    "gz_a", "gz_b",
    "logo_a", "logo_b",
    "cust", "countrycode", "devinfo", "metadata", "misc"
)

# 设备唯一数据：能导，但**不要**刷到别的机器上，会导致 IMEI/校准丢失
$DEVICE_UNIQUE = @("persist", "nvdata", "nvcfg", "nvram", "modemst1", "modemst2", "frp", "efuse", "proinfo")

function Fail($msg) {
    Write-Host ""
    Write-Host "✗ $msg" -ForegroundColor Red
    exit 1
}

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

# ---------------------------------------------------------------- 0. 选设备
if (-not $Serial) {
    $lines = & $Adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
    $serials = @($lines | ForEach-Object { ($_ -split '\s+')[0] } | Where-Object { $_ })
    if ($serials.Count -eq 0) { Fail "没有已授权的设备。请插上 USB 并在手机上允许调试。" }
    if ($serials.Count -gt 1) { Fail "检测到多台设备，请用 -Serial 指定：$($serials -join ', ')" }
    $Serial = $serials[0]
}
Write-Host "device: $Serial"

$model = (& $Adb -s $Serial shell getprop ro.product.model 2>&1) -join ""
$codename = (& $Adb -s $Serial shell getprop ro.product.device 2>&1) -join ""
Write-Host "model : $model ($codename)"

# ---------------------------------------------------------------- 1. 检查 root
Step "check root"
$suTest = (& $Adb -s $Serial shell "su -c id" 2>&1) -join " "
if ($suTest -notmatch "uid=0") {
    Fail "该设备没有可用的 root（su -c id 未返回 uid=0）。`n  纯 ADB 无法读取 /dev/block，需要先 root 设备。`n  当前返回：$suTest"
}
Write-Host "root: OK ($suTest)"

# ---------------------------------------------------------------- 2. 分区清单
Step "list partitions"
$rawList = (& $Adb -s $Serial shell "su -c 'ls /dev/block/by-name'" 2>&1)
$allParts = @($rawList | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -notmatch 'No such' })
if ($allParts.Count -eq 0) { Fail "读不到分区表 /dev/block/by-name" }
Write-Host "共 $($allParts.Count) 个分区"

$sizes = @{}
foreach ($p in $allParts) {
    $sz = (& $Adb -s $Serial shell "su -c 'blockdev --getsize64 /dev/block/by-name/$p 2>/dev/null'" 2>&1) -join ""
    $sz = $sz.Trim()
    if ($sz -match '^\d+$') { $sizes[$p] = [int64]$sz } else { $sizes[$p] = $null }
}

if ($List) {
    Step "分区清单"
    $allParts | Sort-Object | ForEach-Object {
        $s = $sizes[$_]
        if ($s) { "{0,-22} {1,14:N0} 字节  ({2,8:N1} MB)" -f $_, $s, ($s / 1MB) }
        else { "{0,-22} {1}" -f $_, "(大小未知)" }
    }
    exit 0
}

# ---------------------------------------------------------------- 3. 决定导出集
if ($Partitions.Count -gt 0) {
    $targets = $Partitions
} elseif ($Full) {
    $targets = $DEFAULT_SET + $FULL_EXTRA
    if ($IncludeUserData) { $targets += "userdata" }
} else {
    $targets = $DEFAULT_SET
}

# 只保留设备上真实存在的分区
$targets = @($targets | Where-Object { $allParts -contains $_ })
if ($targets.Count -eq 0) { Fail "没有匹配到任何可导出的分区。用 -List 查看实际分区名。" }

$totalBytes = 0
foreach ($t in $targets) { if ($sizes[$t]) { $totalBytes += $sizes[$t] } }

Write-Host ""
Write-Host "将导出 $($targets.Count) 个分区，合计约 $([math]::Round($totalBytes / 1GB, 2)) GB"
Write-Host ($targets -join ", ")

# 提醒设备唯一分区
$risky = @($targets | Where-Object { $DEVICE_UNIQUE -contains $_ })
if ($risky.Count -gt 0) {
    Write-Host ""
    Write-Host "⚠ 包含设备唯一分区：$($risky -join ', ')" -ForegroundColor Yellow
    Write-Host "  这些含 IMEI/校准数据，**不要**刷到其他机器，否则可能变砖。" -ForegroundColor Yellow
}

# ---------------------------------------------------------------- 4. 空间检查
Step "check free space"
$devFree = (& $Adb -s $Serial shell "df -B1 /sdcard 2>/dev/null | tail -1" 2>&1) -join ""
$devFreeBytes = 0
$m = [regex]::Match($devFree, '(\d+)\s+\d+\s+(\d+)\s+(\d+)%')
if ($m.Success) { $devFreeBytes = [int64]$m.Groups[2].Value }
Write-Host "手机 /sdcard 可用: $([math]::Round($devFreeBytes / 1GB, 2)) GB"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$pcDrive = (Get-Item $OutDir).PSDrive
$pcFree = if ($pcDrive) { (Get-PSDrive $pcDrive.Name).Free } else { 0 }
Write-Host "电脑 $($OutDir) 可用: $([math]::Round($pcFree / 1GB, 2)) GB"

if ($devFreeBytes -gt 0 -and $devFreeBytes -lt ($totalBytes * 1.2)) {
    Fail "手机剩余空间不足（需要约 $([math]::Round($totalBytes * 1.2 / 1GB, 2)) GB）"
}
if ($pcFree -gt 0 -and $pcFree -lt ($totalBytes * 1.2)) {
    Fail "电脑剩余空间不足（需要约 $([math]::Round($totalBytes * 1.2 / 1GB, 2)) GB）"
}

# ---------------------------------------------------------------- 5. 导出
Step "dump + pull"
$tmpDir = "/sdcard/kf_dump"
& $Adb -s $Serial shell "su -c 'mkdir -p $tmpDir'" | Out-Null

$manifest = @()
foreach ($p in $targets) {
    $size = $sizes[$p]
    $sizeText = if ($size) { "($([math]::Round($size / 1MB, 1)) MB)" } else { "" }
    Write-Host "`n-- $p $sizeText"

    $remote = "$tmpDir/$p.img"
    & $Adb -s $Serial shell "su -c 'rm -f $remote'" | Out-Null

    $ddOut = (& $Adb -s $Serial shell "su -c 'dd if=/dev/block/by-name/$p of=$remote bs=4M 2>&1'" 2>&1) -join "`n"
    if ($ddOut -notmatch 'copied') {
        Write-Host "   dump 失败：$ddOut" -ForegroundColor Red
        continue
    }

    $local = Join-Path $OutDir "$p.img"
    & $Adb -s $Serial pull $remote $local 2>&1 | Out-Null
    if (-not (Test-Path $local)) {
        Write-Host "   pull 失败" -ForegroundColor Red
        continue
    }

    $len = (Get-Item $local).Length
    $sha = (Get-FileHash -Path $local -Algorithm SHA256).Hash.ToLower()
    Write-Host ("   已保存 {0:N1} MB  sha256={1}" -f ($len / 1MB), $sha.Substring(0, 16))

    $manifest += [pscustomobject]@{
        partition = $p
        bytes     = $len
        sha256    = $sha
        file      = "$p.img"
    }

    if (-not $KeepOnDevice) {
        & $Adb -s $Serial shell "su -c 'rm -f $remote'" | Out-Null
    }
}

# ---------------------------------------------------------------- 6. 清单文件
Step "write manifest"
$meta = [pscustomobject]@{
    dumpedAt = (Get-Date).ToString("o")
    device   = [pscustomobject]@{
        serial   = $Serial
        model    = $model
        codename = $codename
        android  = ((& $Adb -s $Serial shell getprop ro.build.version.release 2>&1) -join "").Trim()
        buildId  = ((& $Adb -s $Serial shell getprop ro.build.display.id 2>&1) -join "").Trim()
    }
    partitions = $manifest
}
$meta | ConvertTo-Json -Depth 6 | Set-Content -Path (Join-Path $OutDir "manifest.json") -Encoding UTF8

@"
固件分区导出说明
================
导出时间：$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
设备    ：$model ($codename)  序列号 $Serial
分区数量：$($manifest.Count)
输出目录：$OutDir

校验方式：
  Get-FileHash .\$($manifest[0].file) -Algorithm SHA256
  与 manifest.json 里的 sha256 对比

注意事项：
1. boot / init_boot / dtbo / vbmeta 用于分析或 Magisk 补丁，不含个人数据。
2. super 是 system/vendor/product 的动态分区，约 8~9GB，是"ROM 主体"。
3. userdata 含全部个人数据（聊天记录、照片、账号），请谨慎保管。
4. persist / nvram / modemst 等含 IMEI 与校准数据，**绝对不要**刷到别的设备。
5. 本工具只读取分区，不修改设备。
"@ | Set-Content -Path (Join-Path $OutDir "README.txt") -Encoding UTF8

Write-Host ""
Write-Host "完成：$($manifest.Count) 个分区已导出到 $OutDir" -ForegroundColor Green
Write-Host "清单：$(Join-Path $OutDir 'manifest.json')"
