# 一键部署脚本：下载最新 CI 产物 -> 卸载旧版 -> 安装 -> 写配置 -> 授权 -> 重启无障碍
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\deploy.ps1
#   powershell -ExecutionPolicy Bypass -File tools\deploy.ps1 -RunId 43700088022
#
# 为什么必须卸载再装：CI 每次构建使用临时 debug 签名，install -r 会因签名不一致失败。
# 为什么必须 force-stop + 开关循环：MIUI 会残留旧的 accessibility 绑定，不清理则收不到事件。

param(
    [string]$RunId = "",
    [string]$Serial = "30ebcf03",
    [string]$Adb = "D:\tools\platform-tools\adb.exe",
    [string]$Repo = "DBkevin/wuzhangai",
    [string]$CenterUrl = "http://10.126.126.3:8099/ingest",
    [string]$Alias = "kf_001"
)

$ErrorActionPreference = "Stop"
$pkg = "com.tencent.mm.assistant"
$svc = "com.tencent.mm.assistant/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

function Step($msg) { Write-Host "`n=== $msg ===" -ForegroundColor Cyan }

# ---------- 1. 找到构建产物 ----------
Step "resolve artifact"
$git = "C:\Program Files\Git\cmd\git.exe"
$cred = "protocol=https`nhost=github.com`n`n" | & $git credential fill 2>&1
$token = ($cred | Select-String '^password=').ToString() -replace '^password=',''
$headers = @{ 'User-Agent' = 'kf-deploy'; 'Authorization' = "Bearer $token"; 'Accept' = 'application/vnd.github+json' }

if (-not $RunId) {
    $runs = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/actions/runs?per_page=10" -Headers $headers -TimeoutSec 30
    $RunId = ($runs.workflow_runs | Where-Object { $_.conclusion -eq 'success' } | Select-Object -First 1).id
}
Write-Host "run id: $RunId"

$dir = Join-Path $env:TEMP "kf-deploy"
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Path $dir -Force | Out-Null

$arts = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repo/actions/runs/$RunId/artifacts" -Headers $headers -TimeoutSec 60
$art = $arts.artifacts | Where-Object { $_.name -eq 'app-debug-apk' } | Select-Object -First 1
if (-not $art) { throw "artifact app-debug-apk not found for run $RunId" }

Invoke-WebRequest -Uri "https://api.github.com/repos/$Repo/actions/artifacts/$($art.id)/zip" -OutFile (Join-Path $dir "apk.zip") -Headers $headers -TimeoutSec 240
Expand-Archive -Path (Join-Path $dir "apk.zip") -DestinationPath $dir -Force
$apk = (Get-ChildItem $dir -Filter *.apk -Recurse | Select-Object -First 1).FullName
Write-Host "apk: $apk ($([math]::Round((Get-Item $apk).Length/1MB,2)) MB)"

# ---------- 2. 唤醒并保持亮屏（MIUI 锁屏会拒绝安装） ----------
Step "wake device"
& $Adb -s $Serial shell input keyevent KEYCODE_WAKEUP | Out-Null
& $Adb -s $Serial shell svc power stayon true | Out-Null
& $Adb -s $Serial shell input swipe 540 1800 540 600 150 | Out-Null
Start-Sleep -Seconds 2

# ---------- 3. 卸载 + 安装 ----------
Step "reinstall"
& $Adb -s $Serial uninstall $pkg | Out-Null
$installOut = & $Adb -s $Serial install $apk 2>&1
Write-Host $installOut
if ($installOut -notmatch 'Success') { throw "install failed: $installOut" }

# ---------- 4. 写入配置 ----------
Step "write prefs"
$prefs = @"
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="local_log_enabled" value="true" />
    <string name="center_url">$CenterUrl</string>
    <string name="service_alias">$Alias</string>
    <int name="max_messages" value="5" />
    <boolean name="ocr_fallback_enabled" value="false" />
    <boolean name="profile_read_enabled" value="true" />
</map>
"@
$tmp = Join-Path $dir "kf_settings.xml"
[System.IO.File]::WriteAllText($tmp, $prefs, (New-Object System.Text.UTF8Encoding($false)))
& $Adb -s $Serial push $tmp /data/local/tmp/kf_settings.xml | Out-Null
& $Adb -s $Serial shell run-as $pkg mkdir -p shared_prefs | Out-Null
& $Adb -s $Serial shell run-as $pkg cp /data/local/tmp/kf_settings.xml shared_prefs/kf_settings.xml | Out-Null
Write-Host "prefs written"

# ---------- 5. 授权 + 重启无障碍 ----------
Step "grant + rebind accessibility"
& $Adb -s $Serial shell appops set $pkg SYSTEM_ALERT_WINDOW allow | Out-Null
& $Adb -s $Serial shell am force-stop $pkg | Out-Null
& $Adb -s $Serial shell settings put secure accessibility_enabled 0 | Out-Null
& $Adb -s $Serial shell settings put secure enabled_accessibility_services '""' | Out-Null
Start-Sleep -Seconds 4
& $Adb -s $Serial shell settings put secure enabled_accessibility_services $svc | Out-Null
& $Adb -s $Serial shell settings put secure accessibility_enabled 1 | Out-Null
Start-Sleep -Seconds 8

Write-Host "`noverlay: $(& $Adb -s $Serial shell appops get $pkg SYSTEM_ALERT_WINDOW)"
Write-Host "a11y: $(& $Adb -s $Serial shell settings get secure enabled_accessibility_services)"

Step "clear logcat and open WeChat"
& $Adb -s $Serial logcat -c | Out-Null
& $Adb -s $Serial shell am start -n com.tencent.mm/.ui.LauncherUI | Out-Null
Start-Sleep -Seconds 6

Step "logs"
& $Adb -s $Serial logcat -d -s KfReadOnly | Where-Object { $_ -match 'KfReadOnly:' } | Select-Object -Last 20

Write-Host "`nDone. 打开一个单聊，或点头像进入资料页，观察浮窗与上面的日志。" -ForegroundColor Green
