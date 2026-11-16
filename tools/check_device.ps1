# ============================================================================
#  root 设备上的 App 运行体检（通用，不绑定具体 App）
#
#  只读为主，-Fix 才动手。用于回答："我的 App 在 root 设备上为什么跑不通/要注意什么"。
#
#  用法：
#    pwsh -File tools\check_device.ps1                      # 列出第三方 App + 设备体检
#    pwsh -File tools\check_device.ps1 -Package com.xxx.app # 针对某个 App 体检
#    pwsh -File tools\check_device.ps1 -Package com.xxx.app -Fix   # 用 root 补齐权限
#    pwsh -File tools\check_device.ps1 -Serial <序列号>
# ============================================================================

param(
    [string]$Serial = "",
    [string]$Package = "",
    [string]$Adb = "D:\tools\platform-tools\adb.exe",
    [switch]$Fix
)

$ErrorActionPreference = "Continue"

function Q($cmd) {
    $out = & $Adb -s $Serial shell $cmd 2>&1
    ($out | Out-String).Trim()
}
function QSu($cmd) {
    $out = & $Adb -s $Serial shell "su -c '$cmd'" 2>&1
    ($out | Out-String).Trim()
}
function Section($t) { Write-Host "`n=== $t ===" -ForegroundColor Cyan }
function Ok($t)      { Write-Host "  [OK]   $t" -ForegroundColor Green }
function Bad($t)     { Write-Host "  [FAIL] $t" -ForegroundColor Red }
function Warn($t)    { Write-Host "  [WARN] $t" -ForegroundColor Yellow }
function Info($t)    { Write-Host "  ·      $t" }

# ---------------------------------------------------------------- 0. 选设备
if (-not $Serial) {
    $lines = & $Adb devices 2>$null | Select-Object -Skip 1 | Where-Object { $_ -match '\sdevice$' }
    $serials = @($lines | ForEach-Object { ($_ -split '\s+')[0] } | Where-Object { $_ })
    if ($serials.Count -eq 0) { Write-Host "没有已授权设备。插 USB，手机上点「允许调试」。" -ForegroundColor Red; exit 1 }
    if ($serials.Count -gt 1) { Write-Host "多台设备，用 -Serial 指定：$($serials -join ', ')" -ForegroundColor Red; exit 1 }
    $Serial = $serials[0]
}

# ---------------------------------------------------------------- 1. 设备
Section "设备"
Info "序列号 $Serial"
Info "型号   $((Q 'getprop ro.product.model').Trim()) / 代号 $((Q 'getprop ro.product.device').Trim())"
Info "系统   Android $((Q 'getprop ro.build.version.release').Trim()) (SDK $((Q 'getprop ro.build.version.sdk').Trim()))"
Info "版本   $((Q 'getprop ro.build.display.id').Trim())"
Info "构建   type=$((Q 'getprop ro.build.type').Trim()) tags=$((Q 'getprop ro.build.tags').Trim())"

# ---------------------------------------------------------------- 2. 解锁 / 完整性
Section "Bootloader 与完整性"
$vbs    = (Q "getprop ro.boot.verifiedbootstate").Trim()
$locked = (Q "getprop ro.boot.flash.locked").Trim()
$verity = (Q "getprop ro.boot.veritymode").Trim()

Info "verifiedbootstate=$vbs  flash.locked=$locked  veritymode=$verity"
if ($vbs -eq "orange" -or $locked -eq "0") {
    Ok "已解锁（green=原厂 / orange=解锁）"
    Warn "Play Integrity 会判不完整：依赖它的服务端风控可能拒绝"
} else {
    Ok "锁定（原厂状态）"
}

# ---------------------------------------------------------------- 3. root
Section "root"
$rootId  = QSu "id"
$hasRoot = $rootId -match "uid=0"
if ($hasRoot) {
    Ok "root 可用：$(($rootId -split "`n")[0])"
} else {
    Bad "root 不可用"
    Info "su -c id 返回：$rootId"
}

$flavors = @()
if ($hasRoot) {
    # 只有在 su 真的可用时才探测 root 方案，否则 su 的报错会被误判成"装了 KernelSU"
    $mv = QSu "magisk -v 2>/dev/null"
    if ($mv -match '^\d') { $flavors += "Magisk $mv" }
    $ksu = QSu "ls -d /data/adb/ksu 2>/dev/null"
    if ($ksu -match '/data/adb/ksu') { $flavors += "KernelSU" }
    $ap = QSu "ls -d /data/adb/ap 2>/dev/null"
    if ($ap -match '/data/adb/ap') { $flavors += "APatch" }
}
$suPath = (Q "which su 2>/dev/null").Trim()
if ($suPath -and $suPath -notmatch 'not found|inaccessible') { $flavors += "su=$suPath" }
if ($flavors.Count) { Info "方案：$($flavors -join ' / ')" }
else { Info "未检测到 root 方案" }

$deny = QSu "magisk --denylist ls 2>/dev/null"
if ($deny -and $deny -notmatch 'No such|not found|Permission denied|^$') {
    $n = @($deny -split "`n" | Where-Object { $_ -match '^[a-zA-Z]' }).Count
    Info "DenyList 条目：$n"
}

# ---------------------------------------------------------------- 4. SELinux
Section "SELinux"
$selinux = (Q "getenforce").Trim()
Info "getenforce = $selinux"
if ($selinux -eq "Enforcing") { Info "强制模式：App 越界访问会被拒（正常且推荐）" }
elseif ($selinux -eq "Permissive") { Warn "宽容模式：不拦截只记录，说明系统被改过" }

# ---------------------------------------------------------------- 5. 列出第三方 App（未指定包名时）
if (-not $Package) {
    Section "第三方 App"
    $raw = Q "pm list packages -3"
    $all = @($raw -split "`n" |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ } |
        ForEach-Object { $_ -replace '^package:', '' } |
        Sort-Object -Unique)
    $all | ForEach-Object { Info $_ }
    Write-Host ""
    Write-Host "  共 $($all.Count) 个；用 -Package <包名> 针对某个 App 深入体检" -ForegroundColor Yellow
    exit 0
}

# ---------------------------------------------------------------- 6. 目标 App 基本信息
Section "App：$Package"
$pmPath = (Q "pm path $Package").Trim()
if ($pmPath -notmatch "package:") { Bad "未安装"; exit 1 }
Ok "已安装"

$dump = Q "dumpsys package $Package"
$verName = ([regex]::Match($dump, 'versionName=(\S+)')).Groups[1].Value
$verCode = ([regex]::Match($dump, 'versionCode=(\d+)')).Groups[1].Value
$tSdk    = ([regex]::Match($dump, 'targetSdk=(\d+)')).Groups[1].Value
$minSdk  = ([regex]::Match($dump, 'minSdk=(\d+)')).Groups[1].Value
$uidLine = ([regex]::Match($dump, 'userId=(\d+)')).Groups[1].Value
Info "版本 $verName ($verCode)  targetSdk=$tSdk minSdk=$minSdk uid=$uidLine"

$debuggable = ($dump -match 'DEBUGGABLE')
if ($debuggable) { Warn "debuggable=true（release 包应为 false）" } else { Ok "debuggable=false" }

$apkPath = ($pmPath -split "`n")[0] -replace '^package:', ''
Info "APK: $apkPath"

# ---------------------------------------------------------------- 7. 权限
Section "权限"
$reqPerms = @([regex]::Matches($dump, 'android\.permission\.[A-Z_]+') | ForEach-Object { $_.Value } | Sort-Object -Unique)
$granted = @()
$denied  = @()
foreach ($p in $reqPerms) {
    $short = $p -replace '^android\.permission\.', ''
    if ($dump -match "$short`: granted=true") { $granted += $short }
    elseif ($dump -match "$short") { $denied += $short }
}
if ($granted.Count) { Ok "已授予：$($granted -join ', ')" }
if ($denied.Count)  { Warn "未授予：$($denied -join ', ')" }

# 危险权限是否被拒（运行时权限）
$runtime = @("POST_NOTIFICATIONS", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "ACCESS_FINE_LOCATION", "CAMERA", "RECORD_AUDIO")
foreach ($r in $runtime) {
    if ($dump -match "$r`: granted=false") { Bad "$r 被拒绝（运行时权限，需 App 内弹窗或 su 授予）" }
}

# ---------------------------------------------------------------- 8. AppOps
Section "AppOps（特殊权限）"
$ops = @("SYSTEM_ALERT_WINDOW", "MANAGE_EXTERNAL_STORAGE", "REQUEST_INSTALL_PACKAGES", "GET_USAGE_STATS", "RUN_IN_BACKGROUND", "WAKE_LOCK")
foreach ($op in $ops) {
    $v = (Q "appops get $Package $op").Trim()
    if ($v -match "allow") { Ok "$op = allow" }
    elseif ($v -match "deny|ignore") { Warn "$op = $($v -replace ".*$op", '').Trim()" }
}

# ---------------------------------------------------------------- 9. 后台与冻结
Section "后台存活"
$idle = (Q "dumpsys deviceidle whitelist").Trim()
if ($idle -match [regex]::Escape($Package)) { Ok "电池优化白名单：在" }
else { Warn "电池优化白名单：不在（国产 ROM 可能杀后台）" }

$pidOut = (Q "pidof $Package").Trim()
if ($pidOut -match '^\d+') {
    Ok "进程存活 pid=$pidOut"
    if ($hasRoot) {
        $cg = QSu "cat /proc/$pidOut/cgroup 2>/dev/null"
        if ($cg -match "frozen") { Bad "进程被冻结（cgroup.freeze=1）" } else { Ok "未被冻结" }
    }
    $oom = QSu "cat /proc/$pidOut/oom_score_adj 2>/dev/null"
    if ($oom) { Info "oom_score_adj=$oom（越小越不容易被杀）" }
} else {
    Info "进程未运行（先打开 App 再看）"
}

# ---------------------------------------------------------------- 10. 崩溃与 SELinux 拒绝
Section "最近崩溃 / SELinux 拒绝"
$crash = & $Adb -s $Serial logcat -d -b crash -t 200 2>&1 | Out-String
$crashLines = @($crash -split "`n" | Where-Object { $_ -match [regex]::Escape($Package) })
if ($crashLines.Count -gt 0) {
    Warn "crash buffer 中有 $($crashLines.Count) 行涉及该 App，最近 5 行："
    $crashLines | Select-Object -Last 5 | ForEach-Object { Info $_.Trim() }
} else { Ok "crash buffer 无该 App 记录" }

$avc = & $Adb -s $Serial logcat -d -t 500 2>&1 | Out-String
$avcLines = @($avc -split "`n" | Where-Object { $_ -match 'avc:.*denied' -and $_ -match [regex]::Escape($Package) })
if ($avcLines.Count -gt 0) {
    Warn "有 $($avcLines.Count) 条 SELinux 拒绝（root 后常见，可能影响功能）："
    $avcLines | Select-Object -Last 3 | ForEach-Object { Info $_.Trim() }
} else { Ok "无相关 SELinux 拒绝" }

# ---------------------------------------------------------------- 11. 修复
if ($Fix) {
    Section "修复"
    if (-not $hasRoot) { Bad "没有 root，无法自动修复" }
    else {
        $perms = @("POST_NOTIFICATIONS", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE")
        foreach ($p in $perms) {
            $o = & $Adb -s $Serial shell "su -c 'pm grant $Package android.permission.$p'" 2>&1 | Out-String
            if ($o -notmatch 'Error|Exception') { Ok "已授予 $p" } else { Info "$p 跳过（$($o.Trim())）" }
        }
        & $Adb -s $Serial shell "su -c 'dumpsys deviceidle whitelist +$Package'" 2>&1 | Out-Null
        Ok "已加入电池优化白名单"
        & $Adb -s $Serial shell "su -c 'appops set $Package SYSTEM_ALERT_WINDOW allow'" 2>&1 | Out-Null
        Ok "已允许悬浮窗"
    }
}

Section "完成"
Write-Host "  以上为环境体检结果。若 App 仍异常，下一步抓具体报错：" -ForegroundColor Cyan
Write-Host "  & `$Adb logcat -c; <操作 App>; & `$Adb logcat -d | Select-String '$Package|AndroidRuntime'" -ForegroundColor DarkGray
