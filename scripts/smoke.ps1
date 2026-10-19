# UniPO 后端冒烟测试（Windows PowerShell 5.1 / curl.exe）
# 用法：powershell -NoProfile -ExecutionPolicy Bypass -File scripts/smoke.ps1
# 前置：后端已在 http://127.0.0.1:8080 运行，本地 MySQL 已初始化（Flyway 建表）
# 说明：本脚本只往本地库写测试数据，跑完可手动清理；全部用 ASCII，避免 PS 5.1 编码问题。

param(
  [string]$Base = 'http://127.0.0.1:8080',
  [string]$AdminUid = '84431313'
)

$ErrorActionPreference = 'Continue'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$script:fail = 0

function CallApi([string]$method, [string]$url, $obj, [string]$token) {
  $args = @('-s','-m','25','-X',$method,$url,'-w','|||%{http_code}')
  if ($null -ne $obj) {
    $tmp = Join-Path $env:TEMP ('smoke_' + [guid]::NewGuid().ToString('N') + '.json')
    [System.IO.File]::WriteAllText($tmp, ($obj | ConvertTo-Json -Depth 6 -Compress), $utf8)
    $args += @('-H','Content-Type: application/json','--data-binary',"@$tmp")
  }
  if ($token) { $args += @('-H', "Authorization: Bearer $token") }
  $out = & curl.exe @args
  if ($null -ne $obj -and (Test-Path $tmp)) { Remove-Item $tmp -ErrorAction SilentlyContinue }
  $parts = $out -split '\|\|\|'
  return [pscustomobject]@{ code = $parts[-1]; body = ($parts[0..($parts.Length-2)] -join '') }
}

function Check([string]$name, $r, [string]$expect = '200') {
  $ok = ($r.code -eq $expect)
  if (-not $ok) { $script:fail++ }
  $flag = if ($ok) { 'PASS' } else { 'FAIL' }
  "{0} {1,-22} http={2}" -f $flag, $name, $r.code
}

$email = 'smoke' + (Get-Random -Maximum 99999) + '@example.com'
$pwd = 'Test@123456'

Check 'verification-code' (CallApi 'POST' "$Base/api/auth/verification-code" @{ email = $email; purpose = 'register' } $null)
$r = CallApi 'POST' "$Base/api/auth/register" @{ email = $email; password = $pwd; confirmPassword = $pwd; nickname = ('Smoke' + (Get-Random -Maximum 9999)); verificationCode = '1234' } $null
Check 'register' $r
$tok = $null
try { $tok = ($r.body | ConvertFrom-Json).data.token } catch {}
if (-not $tok) {
  $r = CallApi 'POST' "$Base/api/auth/login" @{ email = $email; password = $pwd } $null
  Check 'login' $r
  try { $tok = ($r.body | ConvertFrom-Json).data.token } catch {}
}
Check 'me' (CallApi 'GET' "$Base/api/auth/me" $null $tok)

$r = CallApi 'POST' "$Base/api/posts" @{ board='school'; title='Smoke test post'; content='Created by scripts/smoke.ps1.'; tags=@('smoke') } $tok
Check 'create-post' $r
$postId = $null
try { $postId = ($r.body | ConvertFrom-Json).data.id } catch {}

if ($postId) {
  Check 'comment'    (CallApi 'POST' "$Base/api/posts/$postId/comments" @{ content = 'smoke comment' } $tok)
  Check 'like'       (CallApi 'POST' "$Base/api/posts/$postId/like" $null $tok)
  Check 'favorite'   (CallApi 'POST' "$Base/api/posts/$postId/favorite" $null $tok)
}
Check 'check-in'    (CallApi 'POST' "$Base/api/users/me/check-in" $null $tok)
Check 'stats'       (CallApi 'GET'  "$Base/api/users/me/stats" $null $tok)
Check 'hot-today'   (CallApi 'GET'  "$Base/api/posts/hot-today" $null $tok)
Check 'dont-miss'   (CallApi 'GET'  "$Base/api/posts/dont-miss" $null $tok)
Check 'follow'      (CallApi 'POST' "$Base/api/users/$AdminUid/follow" $null $tok)

$r = CallApi 'POST' "$Base/api/messages/conversations" @{ peerUid = $AdminUid } $tok
Check 'conversation' $r
$convId = $null
try { $convId = ($r.body | ConvertFrom-Json).data.id } catch {}
if ($convId) {
  Check 'send-message' (CallApi 'POST' "$Base/api/messages/conversations/$convId/messages" @{ content = 'smoke message' } $tok)
}
Check 'conversations' (CallApi 'GET' "$Base/api/messages/conversations" $null $tok)
Check 'unread-count'  (CallApi 'GET' "$Base/api/messages/unread-count" $null $tok)

# 媒体上传（有本地 MinIO 时）
$png = Join-Path $env:TEMP 'smoke_upload.png'
try {
  Add-Type -AssemblyName System.Drawing -ErrorAction Stop
  $bmp = New-Object System.Drawing.Bitmap 120,80
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.Clear([System.Drawing.Color]::FromArgb(255,86,160,255))
  $g.Dispose(); $bmp.Save($png, [System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
  $up = & curl.exe -s -m 30 -X POST "$Base/api/media/upload" -H ("Authorization: Bearer " + $tok) -F ("file=@" + $png + ";type=image/png") -F "purpose=post"
  if ($up -match '"id"') { 'PASS upload-media           http=200' } else { $script:fail++; 'FAIL upload-media           (see backend log)' }
} catch {
  'SKIP upload-media           (System.Drawing unavailable)'
}

""
"---- failed checks: $script:fail ----"
if ($script:fail -gt 0) { exit 1 }
