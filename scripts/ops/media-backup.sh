#!/usr/bin/env bash
# R04: checksummed archive of one MinIO bucket from a local MinIO container, plus a per-object manifest.
# Run it right AFTER mysql-backup.sh: objects are only ever added, so every object the database dump refers to was
# already in the bucket when the dump was taken; objects uploaded meanwhile are harmless extras.
# Configuration comes only from the environment. See docs/DEPLOY-SERVER.md §8.
set -euo pipefail
umask 077

ops_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/mysql-ops-lib.sh
. "$ops_dir/mysql-ops-lib.sh"

usage() {
  cat <<'EOF'
Usage: UNIPO_MEDIA_BACKUP_DIR=/var/backups/unipo/media UNIPO_MEDIA_BUCKET=campuspo media-backup.sh

Required:
  UNIPO_MEDIA_BACKUP_DIR   absolute target directory (created with mode 700)
  UNIPO_MEDIA_BUCKET       bucket to archive

Optional:
  UNIPO_MEDIA_CONTAINER    MinIO container (default unipo-minio)
  UNIPO_MEDIA_MC_ALIAS     mc alias inside that container (default local, preconfigured by the Bitnami image)
  UNIPO_MEDIA_RETENTION    newest archives of this bucket to keep (default 7)

Writes <bucket>-<UTC>.tar.gz, .tar.gz.sha256 and .manifest.tsv (sha256, size, object key per line).
EOF
}

if [ "$#" -gt 0 ]; then
  case "$1" in
    -h | --help) usage; exit 0 ;;
    *) usage >&2; ops_fail 'This script takes no arguments; configure it through the environment.' ;;
  esac
fi

backup_dir="${UNIPO_MEDIA_BACKUP_DIR:-}"
bucket="${UNIPO_MEDIA_BUCKET:-}"
container="${UNIPO_MEDIA_CONTAINER:-unipo-minio}"
alias_name="${UNIPO_MEDIA_MC_ALIAS:-local}"
retention="${UNIPO_MEDIA_RETENTION:-7}"
[ -n "$backup_dir" ] || ops_fail 'UNIPO_MEDIA_BACKUP_DIR is required.'
[ -n "$bucket" ] || ops_fail 'UNIPO_MEDIA_BUCKET is required.'
case "$backup_dir" in
  /*) ;;
  *) ops_fail 'UNIPO_MEDIA_BACKUP_DIR must be an absolute path.' ;;
esac
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,62}$ ]] || ops_fail 'UNIPO_MEDIA_BUCKET is not a valid bucket name.'
[[ "$alias_name" =~ ^[A-Za-z0-9_-]{1,32}$ ]] || ops_fail 'UNIPO_MEDIA_MC_ALIAS is not a valid alias.'
ops_is_container_name "$container" || ops_fail 'UNIPO_MEDIA_CONTAINER is not a valid container name.'
[[ "$retention" =~ ^[1-9][0-9]{0,3}$ ]] || ops_fail 'UNIPO_MEDIA_RETENTION must be a positive integer.'

ops_require_tools docker gzip tar find awk sort
ops_docker_init
ops_require_running_container "$container"

mkdir -p -- "$backup_dir"
chmod 700 "$backup_dir"

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
base="$bucket-$stamp"
stage_dir=''
partial_archive=''
partial_sum=''
partial_manifest=''
lock_dir=''
cleanup() {
  local result=$?
  trap - EXIT
  [ -z "$stage_dir" ] || rm -rf -- "${stage_dir%/objects}"
  [ -z "$partial_archive" ] || rm -f -- "$partial_archive"
  [ -z "$partial_sum" ] || rm -f -- "$partial_sum"
  [ -z "$partial_manifest" ] || rm -f -- "$partial_manifest"
  [ -z "$lock_dir" ] || rmdir -- "$lock_dir" 2>/dev/null || true
  if [ "$result" != 0 ]; then
    printf '%s Media backup failed; no new archive was kept.\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >&2
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if command -v flock >/dev/null 2>&1; then
  exec 9>"$backup_dir/.$bucket.lock"
  flock -n 9 || ops_fail "Another media backup of $bucket is running."
else
  lock_dir="$backup_dir/.$bucket.lock.d"
  mkdir -- "$lock_dir" 2>/dev/null || ops_fail "Another media backup of $bucket is running."
fi

started="$(date +%s)"
ops_log "Archiving bucket $bucket from $container"
"${ops_docker[@]}" exec "$container" mc stat "$alias_name/$bucket" >/dev/null \
  || ops_fail "Bucket $bucket is not reachable through mc alias $alias_name."

# Each object is streamed out with mc cat, so nothing is written inside the container (the image has no tar, and
# files copied in or out with docker cp end up owned by a uid the MinIO process cannot clean up).
stage_dir="$(mktemp -d "$backup_dir/.stage-$stamp.XXXXXX")"
"${ops_docker[@]}" exec "$container" mc ls --recursive --json "$alias_name/$bucket" >"$stage_dir/.listing"
sed -n 's/.*"key":"\([^"]*\)".*/\1/p' "$stage_dir/.listing" >"$stage_dir/.keys"
listed="$(grep -c '"type":"file"' "$stage_dir/.listing" || true)"
[ "$listed" = "$(wc -l <"$stage_dir/.keys" | tr -d ' ')" ] || ops_fail 'Could not parse every object key from the listing.'
mkdir "$stage_dir/objects"
while IFS= read -r key; do
  case "$key" in
    '' | /* | ../* | */../* | *\\*) ops_fail "Refusing unsafe object key: $key" ;;
  esac
  mkdir -p -- "$stage_dir/objects/$(dirname -- "$key")"
  "${ops_docker[@]}" exec "$container" mc cat "$alias_name/$bucket/$key" >"$stage_dir/objects/$key"
done <"$stage_dir/.keys"
rm -f -- "$stage_dir/.listing" "$stage_dir/.keys"
stage_dir="$stage_dir/objects"
stage_root="${stage_dir%/objects}"

# Object keys may contain spaces but never newlines or tabs (they would break the manifest), so refuse those.
if find "$stage_dir" -type f -name "*$(printf '\t')*" | grep -q .; then
  ops_fail 'An object key contains a tab; the manifest format cannot represent it.'
fi
partial_manifest="$backup_dir/.$base.manifest.tsv.partial"
(
  cd "$stage_dir"
  find . -type f | LC_ALL=C sort | while IFS= read -r object; do
    key="${object#./}"
    size="$(wc -c <"$object" | tr -d ' ')"
    printf '%s\t%s\t%s\n' "$(ops_sha256 "$object")" "$size" "$key"
  done
) >"$partial_manifest"
object_count="$(wc -l <"$partial_manifest" | tr -d ' ')"
total_bytes="$(awk -F '\t' '{s += $2} END {print s + 0}' "$partial_manifest")"

partial_archive="$backup_dir/.$base.tar.gz.partial"
# Only the object bytes go into the archive: no extended attributes or macOS ._ metadata files.
COPYFILE_DISABLE=1 tar --no-xattrs -C "$stage_dir" -czf "$partial_archive" .
gzip -t -- "$partial_archive"
archive_sha="$(ops_sha256 "$partial_archive")"
partial_sum="$backup_dir/.$base.tar.gz.sha256.partial"
printf '%s  %s\n' "$archive_sha" "$base.tar.gz" >"$partial_sum"
rm -rf -- "$stage_root"
stage_dir=''

chmod 600 "$partial_archive" "$partial_sum" "$partial_manifest"
mv -- "$partial_manifest" "$backup_dir/$base.manifest.tsv"
partial_manifest=''
mv -- "$partial_archive" "$backup_dir/$base.tar.gz"
partial_archive=''
mv -- "$partial_sum" "$backup_dir/$base.tar.gz.sha256"
partial_sum=''

# Count-based retention over this bucket's strictly named archives only.
find "$backup_dir" -maxdepth 1 -type f -name "$bucket-[0-9]*T[0-9]*Z.tar.gz" -print \
  | LC_ALL=C sort -r | awk -v keep="$retention" 'NR > keep' | while IFS= read -r old; do
    old_base="${old%.tar.gz}"
    rm -f -- "$old" "$old.sha256" "$old_base.manifest.tsv"
    ops_log "Retention removed $(basename -- "$old")"
  done

ops_log "Media backup ok: $base.tar.gz objects=$object_count bytes=$total_bytes" \
  "archive=$(wc -c <"$backup_dir/$base.tar.gz" | tr -d ' ') sha256=$archive_sha seconds=$(( $(date +%s) - started ))"
