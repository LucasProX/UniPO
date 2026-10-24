#!/usr/bin/env bash
# R04: cross-checks one database against one MinIO bucket and reports objects the database needs but the bucket lacks.
#   - media_objects rows of the bucket whose object is missing
#   - business references (post images, covers, avatars, backgrounds, activity covers, spotlights) that point to a
#     missing object, or to /api/media/{id} with no media_objects row at all
#   - objects nobody records (orphans; informational, never an error)
# Exit 0 when nothing referenced is missing, 3 when something is, 2 on usage or environment errors. Read-only.
set -euo pipefail
umask 077

ops_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/ops/mysql-ops-lib.sh
. "$ops_dir/mysql-ops-lib.sh"

usage() {
  cat <<'EOF'
Usage: media-reference-check.sh --mysql-container NAME --database NAME --minio-container NAME --bucket NAME
                                [--mc-alias local] [--mysql-user root] [--password-file FILE]
EOF
}

mysql_container=''
database=''
minio_container=''
bucket=''
alias_name='local'
mysql_user='root'
password_file=''
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mysql-container) mysql_container="${2:-}"; shift 2 ;;
    --database) database="${2:-}"; shift 2 ;;
    --minio-container) minio_container="${2:-}"; shift 2 ;;
    --bucket) bucket="${2:-}"; shift 2 ;;
    --mc-alias) alias_name="${2:-}"; shift 2 ;;
    --mysql-user) mysql_user="${2:-}"; shift 2 ;;
    --password-file) password_file="${2:-}"; shift 2 ;;
    -h | --help) usage; exit 0 ;;
    *) usage >&2; ops_fail "Unknown argument: $1" ;;
  esac
done
[ -n "$mysql_container" ] && [ -n "$database" ] && [ -n "$minio_container" ] && [ -n "$bucket" ] \
  || { usage >&2; ops_fail 'MySQL container, database, MinIO container and bucket are required.'; }
ops_is_identifier "$database" || ops_fail 'Database must be a plain identifier.'
ops_is_container_name "$mysql_container" || ops_fail 'MySQL container is not a valid name.'
ops_is_container_name "$minio_container" || ops_fail 'MinIO container is not a valid name.'
[[ "$bucket" =~ ^[a-z0-9][a-z0-9.-]{1,62}$ ]] || ops_fail 'Bucket is not a valid bucket name.'
[[ "$alias_name" =~ ^[A-Za-z0-9_-]{1,32}$ ]] || ops_fail 'mc alias is not valid.'

ops_require_tools docker sort comm awk sed
ops_docker_init
ops_require_running_container "$mysql_container"
ops_require_running_container "$minio_container"
ops_credentials_init "$mysql_user" "$password_file"

work="$(mktemp -d)"
trap 'rm -rf -- "$work"; unset UNIPO_OPS_MYSQL_PWD' EXIT

# Objects actually present. mc prints one JSON object per line; keys are generated (purpose/user/uuid.ext).
"${ops_docker[@]}" exec "$minio_container" mc ls --recursive --json "$alias_name/$bucket" \
  | sed -n 's/.*"key":"\([^"]*\)".*/\1/p' | LC_ALL=C sort -u >"$work/objects"

# media_objects rows recorded for this bucket: id<TAB>object_key
ops_sql "$mysql_container" "SELECT id, object_key FROM \`$database\`.media_objects WHERE bucket = '$bucket'" \
  | LC_ALL=C sort -t $'\t' -k2,2 >"$work/media"

# Every business column that may hold a media URL: source<TAB>url. The tables were created by different migrations
# with different collations, so each value is converted before the UNION.
ref_columns="post_images:url posts:cover_url users:avatar_url users:background_url activities:cover_url spotlights:image_url"
ref_sql=''
for ref in $ref_columns; do
  table="${ref%%:*}"; column="${ref#*:}"
  [ -z "$ref_sql" ] || ref_sql="$ref_sql UNION ALL "
  label="$table"; [ "$table" = post_images ] || label="$table.$column"
  ref_sql="${ref_sql}SELECT CONVERT('$label' USING utf8mb4) COLLATE utf8mb4_bin, CONVERT($column USING utf8mb4) COLLATE utf8mb4_bin
    FROM \`$database\`.$table WHERE $column IS NOT NULL AND $column <> ''"
done
ops_sql "$mysql_container" "$ref_sql" >"$work/refs"

# 1) media rows whose object is gone
cut -f2 "$work/media" | LC_ALL=C sort -u >"$work/media_keys"
LC_ALL=C comm -23 "$work/media_keys" "$work/objects" >"$work/missing_keys"
# FILENAME rather than NR == FNR: the first file may legitimately be empty.
awk -F '\t' 'FILENAME == ARGV[1] { missing[$1] = 1; next } ($2 in missing) { print $1 "\t" $2 }' \
  "$work/missing_keys" "$work/media" >"$work/missing_media"

# 2) business references: /api/media/{id}/content (private bucket) or .../<bucket>/<key> (public-read)
awk -F '\t' -v bucket="$bucket" '
  FILENAME == ARGV[1] { key_of[$1] = $2; next }
  FILENAME == ARGV[2] { present[$1] = 1; next }
  {
    source = $1; url = $2
    if (match(url, /\/api\/media\/[0-9]+/)) {
      id = substr(url, RSTART + 11, RLENGTH - 11)
      if (!(id in key_of)) { print "dangling\t" source "\tmedia " id; next }
      if (!(key_of[id] in present)) { print "missing\t" source "\tmedia " id " " key_of[id] }
      next
    }
    marker = "/" bucket "/"
    at = index(url, marker)
    if (at > 0) {
      key = substr(url, at + length(marker)); sub(/\?.*$/, "", key)
      if (!(key in present)) print "missing\t" source "\t" key
    }
  }
' "$work/media" "$work/objects" "$work/refs" >"$work/bad_refs"

# 3) orphans: present but not recorded
LC_ALL=C comm -13 "$work/media_keys" "$work/objects" >"$work/orphans"

objects=$(wc -l <"$work/objects" | tr -d ' ')
media=$(wc -l <"$work/media" | tr -d ' ')
refs=$(wc -l <"$work/refs" | tr -d ' ')
missing_media=$(wc -l <"$work/missing_media" | tr -d ' ')
missing_refs=$(awk -F '\t' '$1 == "missing"' "$work/bad_refs" | wc -l | tr -d ' ')
dangling_refs=$(awk -F '\t' '$1 == "dangling"' "$work/bad_refs" | wc -l | tr -d ' ')
orphans=$(wc -l <"$work/orphans" | tr -d ' ')

ops_log "Media check $database x $bucket: objects=$objects media_rows=$media references=$refs" \
  "missing_media=$missing_media missing_references=$missing_refs dangling_references=$dangling_refs orphans=$orphans"
if [ "$missing_media" != 0 ]; then
  printf 'media_objects rows without an object (id, key):\n'
  sed 's/^/  /' "$work/missing_media"
fi
if [ -s "$work/bad_refs" ]; then
  printf 'references to missing media (kind, source, target):\n'
  sed 's/^/  /' "$work/bad_refs"
fi
if [ "$missing_media" != 0 ] || [ "$missing_refs" != 0 ] || [ "$dangling_refs" != 0 ]; then
  exit 3
fi
