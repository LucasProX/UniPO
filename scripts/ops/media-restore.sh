#!/usr/bin/env bash
# R04: restores a media-backup.sh archive into an EMPTY bucket of a local MinIO container.
# Everything about the archive (checksum file, gzip, path safety, manifest hashes) is verified before MinIO is touched.
set -euo pipefail
umask 077

ops_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/mysql-ops-lib.sh
. "$ops_dir/mysql-ops-lib.sh"

usage() {
  cat <<'EOF'
Usage: media-restore.sh --archive FILE.tar.gz --container NAME --bucket NAME [--mc-alias local] [--allow-production-target]

The archive needs its .sha256 and .manifest.tsv next to it. The target bucket must be missing or empty.
Targets that look like production (container unipo-minio, bucket campuspo, or a prod/production/live name part)
are refused unless --allow-production-target is given.
EOF
}

archive=''
container=''
bucket=''
alias_name='local'
allow_production=0
while [ "$#" -gt 0 ]; do
  case "$1" in
    --archive) archive="${2:-}"; shift 2 ;;
    --container) container="${2:-}"; shift 2 ;;
    --bucket) bucket="${2:-}"; shift 2 ;;
    --mc-alias) alias_name="${2:-}"; shift 2 ;;
    --allow-production-target) allow_production=1; shift ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; ops_fail "Unknown argument: $1" ;;
  esac
done
[ -n "$archive" ] && [ -n "$container" ] && [ -n "$bucket" ] || { usage >&2; ops_fail 'Archive, container and bucket are required.'; }
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,62}$ ]] || ops_fail 'Bucket is not a valid bucket name.'
[[ "$alias_name" =~ ^[A-Za-z0-9_-]{1,32}$ ]] || ops_fail 'mc alias is not valid.'
ops_is_container_name "$container" || ops_fail 'Container is not a valid container name.'

looks_production=0
[ "$container" = unipo-minio ] && looks_production=1
[ "$bucket" = campuspo ] && looks_production=1
if [[ "$container-$bucket" =~ (^|[-_.])(prod|production|live)([-_.]|$) ]]; then looks_production=1; fi
if [ "$looks_production" = 1 ] && [ "$allow_production" != 1 ]; then
  ops_fail 'Target looks like production; pass --allow-production-target only after confirming this is intended.'
fi

ops_require_tools docker gzip tar find awk sort
case "$archive" in
  *.tar.gz) ;;
  *) ops_fail 'Archive must be a .tar.gz produced by media-backup.sh.' ;;
esac
[ -f "$archive" ] && [ ! -L "$archive" ] || ops_fail 'Archive must be a regular file.'
sum_file="$archive.sha256"
manifest="${archive%.tar.gz}.manifest.tsv"
[ -f "$sum_file" ] || ops_fail 'Checksum file is missing.'
[ -f "$manifest" ] || ops_fail 'Manifest file is missing.'

expected_line="$(head -n 1 -- "$sum_file")"
expected_sha="${expected_line%%  *}"
expected_name="${expected_line#*  }"
[ "$expected_name" = "$(basename -- "$archive")" ] || ops_fail 'Checksum file names a different archive.'
[ "$(ops_sha256 "$archive")" = "$expected_sha" ] || ops_fail 'Archive checksum mismatch.'
gzip -t -- "$archive" || ops_fail 'Archive is not a readable gzip file.'
# Only relative, non-escaping regular entries are allowed.
if tar -tzf "$archive" | awk '/^\// || /(^|\/)\.\.(\/|$)/ { bad = 1 } END { exit bad ? 0 : 1 }'; then
  ops_fail 'Archive contains absolute or parent-relative paths.'
fi

stage_dir="$(mktemp -d)"
cleanup() {
  local result=$?
  trap - EXIT
  rm -rf -- "$stage_dir"
  [ "$result" = 0 ] || printf '%s Media restore failed.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >&2
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

tar -xzf "$archive" -C "$stage_dir"
if find "$stage_dir" ! -type f ! -type d | grep -q .; then
  ops_fail 'Archive contains links or special files.'
fi
mismatches=0
while IFS=$'\t' read -r sha size key; do
  object="$stage_dir/$key"
  if [ ! -f "$object" ] || [ "$(ops_sha256 "$object")" != "$sha" ] || [ "$(wc -c <"$object" | tr -d ' ')" != "$size" ]; then
    mismatches=$((mismatches + 1))
  fi
done <"$manifest"
manifest_count="$(wc -l <"$manifest" | tr -d ' ')"
archive_count="$(find "$stage_dir" -type f | wc -l | tr -d ' ')"
[ "$mismatches" = 0 ] || ops_fail "$mismatches objects do not match the manifest."
[ "$manifest_count" = "$archive_count" ] || ops_fail 'Archive and manifest list a different number of objects.'
ops_log "Archive verified: $manifest_count objects"

ops_docker_init
ops_require_running_container "$container"
if "${ops_docker[@]}" exec "$container" mc stat "$alias_name/$bucket" >/dev/null 2>&1; then
  existing="$("${ops_docker[@]}" exec "$container" mc ls --recursive "$alias_name/$bucket" | wc -l | tr -d ' ')"
  [ "$existing" = 0 ] || ops_fail "Target bucket $bucket is not empty ($existing objects); restore only into an empty bucket."
else
  "${ops_docker[@]}" exec "$container" mc mb "$alias_name/$bucket" >/dev/null
fi

# Each object is streamed in with mc pipe, so no copy is left inside the container.
while IFS=$'\t' read -r _sha _size key; do
  "${ops_docker[@]}" exec -i "$container" mc pipe --quiet "$alias_name/$bucket/$key" <"$stage_dir/$key" >/dev/null
done <"$manifest"

restored="$("${ops_docker[@]}" exec "$container" mc ls --recursive "$alias_name/$bucket" | wc -l | tr -d ' ')"
[ "$restored" = "$manifest_count" ] || ops_fail "Restored $restored objects, manifest lists $manifest_count."
ops_log "Media restore ok: bucket=$bucket objects=$restored"
