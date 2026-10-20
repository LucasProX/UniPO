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
# ---- /api/v1 版本化契约 ----
# 服务端同时挂 /api 与 /api/v1，前端已切到 /api/v1（见 docs/MOBILE-API-PLAN.md 4.1）。
# 这里单独验证：1) 前缀可用；2) 游标分页能完整走完且不重复不漏。
$v1 = ($Base.TrimEnd('/')) + '/api/v1'
Check 'v1-posts'      (CallApi 'GET' "$v1/posts?tab=recommend" $null $tok)
Check 'v1-boards'     (CallApi 'GET' "$v1/posts/boards" $null $tok)
Check 'v1-hot-today'  (CallApi 'GET' "$v1/posts/hot-today" $null $tok)
Check 'v1-dont-miss'  (CallApi 'GET' "$v1/posts/dont-miss" $null $tok)
Check 'v1-me'         (CallApi 'GET' "$v1/auth/me" $null $tok)

# 游标分页：limit=5 连拉多页，累计 id 必须无重复（重复说明游标键没编码完整排序键）
$r = CallApi 'GET' "$v1/posts/page?tab=recommend&limit=5" $null $tok
Check 'v1-page-1' $r
$seen = New-Object System.Collections.Generic.List[string]
$cursor = $null
$pages = 0
try {
  $page = ($r.body | ConvertFrom-Json).data
  $cursor = $page.nextCursor
  foreach ($item in $page.items) { [void]$seen.Add([string]$item.id) }
  $pages = 1
} catch {}
while ($cursor -and $pages -lt 10) {
  $r = CallApi 'GET' ("$v1/posts/page?tab=recommend&limit=5&cursor=" + [uri]::EscapeDataString($cursor)) $null $tok
  if ($r.code -ne '200') { Check ('v1-page-' + ($pages + 1)) $r; break }
  try {
    $page = ($r.body | ConvertFrom-Json).data
    foreach ($item in $page.items) { [void]$seen.Add([string]$item.id) }
    $cursor = $page.nextCursor
    $pages++
  } catch { break }
}
$dupes = ($seen | Group-Object | Where-Object { $_.Count -gt 1 }).Count
if ($dupes -eq 0 -and $seen.Count -gt 0) {
  'PASS v1-page-walk            pages=' + $pages + ' items=' + $seen.Count + ' dupes=0'
} else {
  $script:fail++
  'FAIL v1-page-walk            pages=' + $pages + ' items=' + $seen.Count + ' dupes=' + $dupes
}

# 非法游标必须 400，不能静默当成第一页
$r = CallApi 'GET' "$v1/posts/page?tab=recommend&cursor=not-a-cursor" $null $tok
Check 'v1-page-bad-cursor' $r '400'

# ---- 统一响应包 / 时区契约 ----
# 这些是 App 端契约的地基，改动会静默影响所有接口，单独钉死。
$r = CallApi 'GET' "$v1/posts?tab=recommend" $null $tok
$env_ok = $false; $env_code = ''; $env_rid = ''
try {
  $body = $r.body | ConvertFrom-Json
  $env_code = [string]$body.code
  $env_rid = [string]$body.requestId
  $env_ok = ($env_code -eq 'OK') -and ($env_rid.Length -ge 8) -and ($r.body -match '"message"')
} catch {}
if ($env_ok) { 'PASS envelope-success         code=OK requestId=' + $env_rid.Substring(0,8) }
else { $script:fail++; 'FAIL envelope-success         code=' + $env_code + ' requestId=' + $env_rid }

# 错误包必须有业务码（不能只有中文 message）
$r = CallApi 'GET' "$v1/posts/page?cursor=not-a-cursor" $null $tok
$err_ok = $false; $err_code = ''
try {
  $body = $r.body | ConvertFrom-Json
  $err_code = [string]$body.code
  $err_ok = ($r.code -eq '400') -and ($err_code -eq 'VALIDATION_FAILED') -and ([string]$body.requestId).Length -ge 8
} catch {}
if ($err_ok) { 'PASS envelope-error           code=' + $err_code }
else { $script:fail++; 'FAIL envelope-error           http=' + $r.code + ' code=' + $err_code }

# 未登录 401 也要走同一套错误包（Security 过滤器链里手写的那份）
$r = CallApi 'GET' "$v1/auth/me" $null $null
$auth_ok = $false; $auth_code = ''
try {
  $body = $r.body | ConvertFrom-Json
  $auth_code = [string]$body.code
  $auth_ok = ($r.code -eq '401') -and ($auth_code -eq 'AUTH_UNAUTHENTICATED')
} catch {}
if ($auth_ok) { 'PASS envelope-401             code=' + $auth_code }
else { $script:fail++; 'FAIL envelope-401             http=' + $r.code + ' code=' + $auth_code }

# 时间字段必须带时区偏移，否则 App 跨时区会显示错时间
$r = CallApi 'GET' "$v1/posts?tab=recommend" $null $tok
$tz_ok = $false; $tz_sample = ''
try {
  $first = ($r.body | ConvertFrom-Json).data[0]
  $tz_sample = [string]$first.publishedAt
  $tz_ok = ($tz_sample -match 'T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$')
} catch {}
if ($tz_ok) { 'PASS time-offset              ' + $tz_sample }
else { $script:fail++; 'FAIL time-offset              ' + $tz_sample }

# ---- 开发期随手注册：账号 + 密码即可，不要验证码、不要昵称 ----
$bare = 'bare' + (Get-Random -Maximum 99999)
$r = CallApi 'POST' "$v1/auth/register" @{ email = $bare; password = '1' } $null
$bare_ok = $false; $bare_nick = ''
try {
  $b = $r.body | ConvertFrom-Json
  $bare_nick = [string]$b.data.user.nickname
  $bare_ok = ($r.code -eq '200') -and ($bare_nick -eq $bare)
} catch {}
if ($bare_ok) { 'PASS register-minimal        nickname=' + $bare_nick }
else { $script:fail++; 'FAIL register-minimal        http=' + $r.code }

# 刚建的号能登录（证明真的落库了）
$r = CallApi 'POST' "$v1/auth/login" @{ account = $bare; password = '1' } $null
$login_ok = $false
try { $login_ok = ($r.code -eq '200') -and (($r.body | ConvertFrom-Json).code -eq 'OK') } catch {}
if ($login_ok) { 'PASS register-minimal-login  ' + $bare }
else { $script:fail++; 'FAIL register-minimal-login  http=' + $r.code }

# 重名必须被拒（这是唯一的约束）
$r = CallApi 'POST' "$v1/auth/register" @{ account = $bare; password = 'other' } $null
if ($r.code -eq '400') { 'PASS register-duplicate      http=400' }
else { $script:fail++; 'FAIL register-duplicate      http=' + $r.code }

# 密码超过 BCrypt 的 72 字节要明确拒绝，不能静默截断
$r = CallApi 'POST' "$v1/auth/register" @{ account = ('long' + (Get-Random -Maximum 99999)); password = ('a' * 73) } $null
if ($r.code -eq '400') { 'PASS register-longpassword   http=400' }
else { $script:fail++; 'FAIL register-longpassword   http=' + $r.code }

"---- failed checks: $script:fail ----"
if ($script:fail -gt 0) { exit 1 }
