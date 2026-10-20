#!/usr/bin/env bash
# UniPO backend smoke test (macOS/Linux: bash + curl + jq)
# Usage: scripts/smoke.sh [base-url]
#   BASE=http://127.0.0.1:8080 scripts/smoke.sh
#   ADMIN_UID=84431313 scripts/smoke.sh
# Prereq: backend is running on $BASE and local MySQL is initialized (Flyway).
# Writes test data to the local DB only. Comments may be Chinese; assertions stay ASCII.
set -u

BASE="${1:-${BASE:-http://127.0.0.1:8080}}"
# Peer uid for follow/conversation. Empty means "log in as the bootstrap admin and
# read its publicUid" -- a fresh database assigns a random uid, so hardcoding one
# only works on the machine that created it.
ADMIN_UID="${ADMIN_UID:-}"
FAIL=0
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

if ! command -v curl >/dev/null 2>&1; then echo "curl is required" >&2; exit 2; fi
if ! command -v jq >/dev/null 2>&1; then echo "jq is required (brew install jq)" >&2; exit 2; fi

HTTP_CODE=""
BODY=""

# call_api METHOD URL [JSON_BODY] [TOKEN]
# Sets HTTP_CODE and BODY (the last line of curl -w output is the status code).
call_api() {
  local method="$1" url="$2" json="${3:-}" token="${4:-}"
  local -a args=(-sS -m 25 -X "$method" "$url" -w $'\n%{http_code}')
  if [ -n "$json" ]; then
    printf '%s' "$json" > "$TMP_DIR/body.json"
    args+=(-H 'Content-Type: application/json' --data-binary "@$TMP_DIR/body.json")
  fi
  if [ -n "$token" ]; then
    args+=(-H "Authorization: Bearer $token")
  fi
  local out
  out="$(curl "${args[@]}" 2>/dev/null)" || true
  HTTP_CODE="${out##*$'\n'}"
  BODY="${out%$'\n'*}"
}

# jget FILTER: pull a value out of BODY; empty string when absent/null.
jget() {
  printf '%s' "$BODY" | jq -r "$1 // empty" 2>/dev/null || true
}

check() {
  local name="$1" expect="${2:-200}"
  if [ "$HTTP_CODE" = "$expect" ]; then
    printf 'PASS %-24s http=%s\n' "$name" "$HTTP_CODE"
  else
    FAIL=$((FAIL + 1))
    printf 'FAIL %-24s http=%s (want %s)\n' "$name" "$HTTP_CODE" "$expect"
  fi
}

pass_raw() { printf 'PASS %s\n' "$1"; }
fail_raw() { FAIL=$((FAIL + 1)); printf 'FAIL %s\n' "$1"; }

rand() { printf '%s' "$((RANDOM % 100000))"; }

EMAIL="smoke$(rand)@example.com"
PWD_MAIN='Test@123456'
V1="${BASE%/}/api/v1"

# ---- auth basics ----
call_api POST "$BASE/api/auth/verification-code" "$(jq -nc --arg e "$EMAIL" '{email:$e,purpose:"register"}')"
check verification-code

call_api POST "$BASE/api/auth/register" \
  "$(jq -nc --arg e "$EMAIL" --arg p "$PWD_MAIN" --arg n "Smoke$(rand)" \
    '{email:$e,password:$p,confirmPassword:$p,nickname:$n,verificationCode:"1234"}')"
# 20 registrations per hour per IP (RateLimitFilter). Running this script a few times
# in a row hits that on purpose -- stop early instead of cascading 20 meaningless 401s.
if [ "$HTTP_CODE" = "429" ]; then
  echo "ABORT: register rate-limited (429). Wait for the window, or set RATE_LIMIT_ENABLED=false in .env for local runs." >&2
  exit 3
fi
check register

TOK="$(jget '.data.token')"
AUTH_RESP="$BODY"
if [ -z "$TOK" ]; then
  call_api POST "$BASE/api/auth/login" "$(jq -nc --arg e "$EMAIL" --arg p "$PWD_MAIN" '{email:$e,password:$p}')"
  check login
  TOK="$(jget '.data.token')"
  AUTH_RESP="$BODY"
fi
call_api GET "$BASE/api/auth/me" "" "$TOK"
check me

# refresh token: exchange refresh -> new access; an access token must be rejected.
REFRESH_TOK="$(printf '%s' "$AUTH_RESP" | jq -r '.data.refreshToken // empty' 2>/dev/null || true)"
call_api POST "$V1/auth/refresh" "$(jq -nc --arg r "$REFRESH_TOK" '{refreshToken:$r}')"
check refresh
NEW_TOK="$(jget '.data.token')"
if [ -n "$NEW_TOK" ]; then
  call_api GET "$BASE/api/auth/me" "" "$NEW_TOK"
  check refresh-me
else
  fail_raw 'refresh-no-token'
fi
call_api POST "$V1/auth/refresh" "$(jq -nc --arg r "$TOK" '{refreshToken:$r}')"
check refresh-rejects-access 401

# ---- posts and interactions ----
call_api POST "$BASE/api/posts" \
  '{"board":"school","title":"Smoke test post","content":"Created by scripts/smoke.sh.","tags":["smoke"]}' "$TOK"
check create-post
POST_ID="$(jget '.data.id')"

if [ -n "$POST_ID" ]; then
  call_api POST "$BASE/api/posts/$POST_ID/comments" '{"content":"smoke comment"}' "$TOK"
  check comment
  call_api POST "$BASE/api/posts/$POST_ID/like" "" "$TOK"
  check like
  call_api POST "$BASE/api/posts/$POST_ID/favorite" "" "$TOK"
  check favorite
fi

call_api POST "$BASE/api/users/me/check-in" "" "$TOK"
check check-in
call_api GET "$BASE/api/users/me/stats" "" "$TOK"
check stats
call_api GET "$BASE/api/posts/hot-today" "" "$TOK"
check hot-today
call_api GET "$BASE/api/posts/dont-miss" "" "$TOK"
check dont-miss
if [ -z "$ADMIN_UID" ]; then
  call_api POST "$BASE/api/auth/login" '{"email":"admin@biecuoguo.local","password":"Admin@123456"}'
  ADMIN_UID="$(jget '.data.user.publicUid')"
fi
if [ -n "$ADMIN_UID" ]; then
  call_api POST "$BASE/api/users/$ADMIN_UID/follow" "" "$TOK"
  check follow
else
  echo 'SKIP follow                 (admin login failed)'
fi

# ---- messages ----
if [ -n "$ADMIN_UID" ]; then
  call_api POST "$BASE/api/messages/conversations" "$(jq -nc --arg u "$ADMIN_UID" '{peerUid:$u}')" "$TOK"
  check conversation
else
  echo 'SKIP conversation           (admin login failed)'
fi
CONV_ID="$(jget '.data.id')"
if [ -n "$CONV_ID" ]; then
  call_api POST "$BASE/api/messages/conversations/$CONV_ID/messages" '{"content":"smoke message"}' "$TOK"
  check send-message
fi
call_api GET "$BASE/api/messages/conversations" "" "$TOK"
check conversations
call_api GET "$BASE/api/messages/unread-count" "" "$TOK"
check unread-count

# ---- media upload (needs local MinIO) ----
# 1x1 transparent PNG; backend identifies type by magic bytes, not by filename.
# CI has no MinIO: set SMOKE_SKIP_UPLOAD=1 to skip instead of failing.
PNG_B64='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='
if [ "${SMOKE_SKIP_UPLOAD:-0}" = "1" ]; then
  echo 'SKIP upload-media           (SMOKE_SKIP_UPLOAD=1)'
elif printf '%s' "$PNG_B64" | base64 --decode > "$TMP_DIR/upload.png" 2>/dev/null; then
  UP="$(curl -sS -m 30 -X POST "$BASE/api/media/upload" \
    -H "Authorization: Bearer $TOK" \
    -F "file=@$TMP_DIR/upload.png;type=image/png" -F 'purpose=post' 2>/dev/null || true)"
  if printf '%s' "$UP" | jq -e '.data.id' >/dev/null 2>&1; then
    pass_raw 'upload-media             http=200'
  else
    fail_raw 'upload-media             (see backend log)'
  fi
else
  echo 'SKIP upload-media           (base64 unavailable)'
fi

echo

# ---- /api/v1 contract ----
call_api GET "$V1/posts?tab=recommend" "" "$TOK"
check v1-posts
call_api GET "$V1/posts/boards" "" "$TOK"
check v1-boards
call_api GET "$V1/posts/hot-today" "" "$TOK"
check v1-hot-today
call_api GET "$V1/posts/dont-miss" "" "$TOK"
check v1-dont-miss
call_api GET "$V1/auth/me" "" "$TOK"
check v1-me

# Cursor pagination: walk up to 10 pages and require no duplicated ids.
call_api GET "$V1/posts/page?tab=recommend&limit=5" "" "$TOK"
check v1-page-1
PAGE_BODY="$BODY"
CURSOR="$(printf '%s' "$PAGE_BODY" | jq -r '.data.nextCursor // empty' 2>/dev/null || true)"
SEEN_FILE="$TMP_DIR/seen.txt"
: > "$SEEN_FILE"
printf '%s' "$PAGE_BODY" | jq -r '.data.items[]?.id' >> "$SEEN_FILE" 2>/dev/null || true
PAGES=1
while [ -n "$CURSOR" ] && [ "$PAGES" -lt 10 ]; do
  ENC_CURSOR="$(jq -rn --arg c "$CURSOR" '$c|@uri')"
  call_api GET "$V1/posts/page?tab=recommend&limit=5&cursor=$ENC_CURSOR" "" "$TOK"
  if [ "$HTTP_CODE" != "200" ]; then
    check "v1-page-$((PAGES + 1))"
    break
  fi
  printf '%s' "$BODY" | jq -r '.data.items[]?.id' >> "$SEEN_FILE" 2>/dev/null || true
  CURSOR="$(jget '.data.nextCursor')"
  PAGES=$((PAGES + 1))
done
TOTAL_ITEMS="$(grep -c . "$SEEN_FILE" || true)"
DUPES="$(sort "$SEEN_FILE" | uniq -d | grep -c . || true)"
if [ "${DUPES:-0}" -eq 0 ] && [ "${TOTAL_ITEMS:-0}" -gt 0 ]; then
  pass_raw "v1-page-walk            pages=$PAGES items=$TOTAL_ITEMS dupes=0"
else
  fail_raw "v1-page-walk            pages=$PAGES items=$TOTAL_ITEMS dupes=${DUPES:-0}"
fi

# Invalid cursor must be 400, not silently treated as page 1.
call_api GET "$V1/posts/page?tab=recommend&cursor=not-a-cursor" "" "$TOK"
check v1-page-bad-cursor 400

# ---- envelope / timezone contract ----
call_api GET "$V1/posts?tab=recommend" "" "$TOK"
ENV_CODE="$(jget '.code')"
ENV_RID="$(jget '.requestId')"
if [ "$ENV_CODE" = "OK" ] && [ "${#ENV_RID}" -ge 8 ] && printf '%s' "$BODY" | jq -e 'has("message")' >/dev/null 2>&1; then
  pass_raw "envelope-success         code=OK requestId=${ENV_RID:0:8}"
else
  fail_raw "envelope-success         code=$ENV_CODE requestId=$ENV_RID"
fi

call_api GET "$V1/posts/page?cursor=not-a-cursor" "" "$TOK"
ERR_CODE="$(jget '.code')"
ERR_RID="$(jget '.requestId')"
if [ "$HTTP_CODE" = "400" ] && [ "$ERR_CODE" = "VALIDATION_FAILED" ] && [ "${#ERR_RID}" -ge 8 ]; then
  pass_raw "envelope-error           code=$ERR_CODE"
else
  fail_raw "envelope-error           http=$HTTP_CODE code=$ERR_CODE"
fi

call_api GET "$V1/auth/me" "" ""
AUTH_ERR_CODE="$(jget '.code')"
if [ "$HTTP_CODE" = "401" ] && [ "$AUTH_ERR_CODE" = "AUTH_UNAUTHENTICATED" ]; then
  pass_raw "envelope-401             code=$AUTH_ERR_CODE"
else
  fail_raw "envelope-401             http=$HTTP_CODE code=$AUTH_ERR_CODE"
fi

call_api GET "$V1/posts?tab=recommend" "" "$TOK"
TZ_SAMPLE="$(jget '.data[0].publishedAt')"
if [[ "$TZ_SAMPLE" =~ T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{2}:[0-9]{2}$ ]]; then
  pass_raw "time-offset              $TZ_SAMPLE"
else
  fail_raw "time-offset              $TZ_SAMPLE"
fi

# ---- dev registration rules ----
BARE="bare$(rand)"
call_api POST "$V1/auth/register" "$(jq -nc --arg e "$BARE" '{email:$e,password:"1",verificationCode:"1234"}')"
BARE_NICK="$(jget '.data.user.nickname')"
if [ "$HTTP_CODE" = "200" ] && [ "$BARE_NICK" = "$BARE" ]; then
  pass_raw "register-minimal        nickname=$BARE_NICK"
else
  fail_raw "register-minimal        http=$HTTP_CODE"
fi

call_api POST "$V1/auth/login" "$(jq -nc --arg a "$BARE" '{account:$a,password:"1"}')"
LOGIN_CODE="$(jget '.code')"
if [ "$HTTP_CODE" = "200" ] && [ "$LOGIN_CODE" = "OK" ]; then
  pass_raw "register-minimal-login  $BARE"
else
  fail_raw "register-minimal-login  http=$HTTP_CODE"
fi

call_api POST "$V1/auth/register" "$(jq -nc --arg a "$BARE" '{account:$a,password:"other",verificationCode:"1234"}')"
if [ "$HTTP_CODE" = "400" ]; then pass_raw 'register-duplicate      http=400'; else fail_raw "register-duplicate      http=$HTTP_CODE"; fi

# Verification code is mandatory in BOTH modes; dev mode only accepts any 4-digit value.
call_api POST "$V1/auth/register" "$(jq -nc --arg e "nocode$(rand)" '{email:$e,password:"1"}')"
if [ "$HTTP_CODE" = "400" ]; then pass_raw 'register-nocode         http=400'; else fail_raw "register-nocode         http=$HTTP_CODE"; fi

# Passwords over BCrypt's 72-byte limit must be rejected explicitly.
LONG_PWD="$(printf 'a%.0s' $(seq 1 73))"
call_api POST "$V1/auth/register" "$(jq -nc --arg a "long$(rand)" --arg p "$LONG_PWD" '{account:$a,password:$p,verificationCode:"1234"}')"
if [ "$HTTP_CODE" = "400" ]; then pass_raw 'register-longpassword   http=400'; else fail_raw "register-longpassword   http=$HTTP_CODE"; fi

echo
echo "---- failed checks: $FAIL ----"
if [ "$FAIL" -gt 0 ]; then exit 1; fi
exit 0
