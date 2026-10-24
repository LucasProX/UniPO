#!/usr/bin/env bash
# R04 drill: a migrated MySQL plus a MinIO bucket with real image bytes referenced by post images, an avatar and a
# public-style URL are backed up together (mysql-backup.sh, then media-backup.sh), restored into fresh containers
# (mysql-restore.sh, media-restore.sh) and cross-checked with media-reference-check.sh. A deleted object must then be
# reported. Never imports .env, never touches unipo-mysql / unipo-minio or any remote host; only this run's labelled
# containers are created and removed.
set -euo pipefail
umask 077

task_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
fixture_file="$task_root/scripts/integration/media-backup-restore.compose.yml"
ops="$task_root/scripts/ops"
migration_dir="$task_root/backend/src/main/resources/db/migration"
for tool in docker openssl lsof java unzip gzip tar awk; do
  command -v "$tool" >/dev/null 2>&1 || { printf 'Missing required tool: %s\n' "$tool" >&2; exit 2; }
done
# shellcheck source=scripts/ops/mysql-ops-lib.sh
. "$ops/mysql-ops-lib.sh"
ops_docker_init

if lsof -nP -iTCP:22317 -sTCP:LISTEN >/dev/null 2>&1; then
  printf 'Port 22317 is already in use; no service was changed.\n' >&2
  exit 2
fi
export UNIPO_R04_MYSQL_IMAGE="${UNIPO_R04_MYSQL_IMAGE:-docker.m.daocloud.io/library/mysql:8.4}"
export UNIPO_R04_MINIO_IMAGE="${UNIPO_R04_MINIO_IMAGE:-bitnamilegacy/minio:latest}"
for image in "$UNIPO_R04_MYSQL_IMAGE" "$UNIPO_R04_MINIO_IMAGE"; do
  "${ops_docker[@]}" image inspect "$image" >/dev/null 2>&1 \
    || { printf 'Required cached image %s is missing; no image was pulled.\n' "$image" >&2; exit 2; }
done
jar="${UNIPO_R04_BACKEND_JAR:-$task_root/backend/target/biecuoguo-backend-0.1.0.jar}"
[ -f "$jar" ] || { printf 'Backend jar not found; run mvn -DskipTests package in backend first.\n' >&2; exit 2; }

# The Flyway fixture shared with R03 reads these names.
UNIPO_R03_RUN_ID="$(openssl rand -hex 8)"
UNIPO_R03_SOURCE_PASSWORD="$(openssl rand -hex 24)"
export UNIPO_R03_RUN_ID UNIPO_R03_SOURCE_PASSWORD
export UNIPO_R03_SCHEMA="unipo_r04_$UNIPO_R03_RUN_ID"
export UNIPO_R03_EXPECTED_VERSION
UNIPO_R03_EXPECTED_VERSION="$(find "$migration_dir" -maxdepth 1 -name 'V*__*.sql' -exec basename {} \; \
  | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | tail -n 1)"
UNIPO_R04_SOURCE_ROOT_PASSWORD="$(openssl rand -hex 24)"
UNIPO_R04_TARGET_ROOT_PASSWORD="$(openssl rand -hex 24)"
UNIPO_R04_MINIO_PASSWORD="$(openssl rand -hex 24)"
export UNIPO_R04_SOURCE_ROOT_PASSWORD UNIPO_R04_TARGET_ROOT_PASSWORD UNIPO_R04_MINIO_PASSWORD
export UNIPO_R04_SOURCE_MYSQL="unipo-r04-srcdb-$UNIPO_R03_RUN_ID"
export UNIPO_R04_TARGET_MYSQL="unipo-r04-dstdb-$UNIPO_R03_RUN_ID"
export UNIPO_R04_SOURCE_MINIO="unipo-r04-srcs3-$UNIPO_R03_RUN_ID"
export UNIPO_R04_TARGET_MINIO="unipo-r04-dsts3-$UNIPO_R03_RUN_ID"
export UNIPO_R04_SOURCE_BUCKET="r04-media-$UNIPO_R03_RUN_ID"
target_bucket="r04-restored-$UNIPO_R03_RUN_ID"
restore_database="unipo_restore_$UNIPO_R03_RUN_ID"
project="unipo-r04-$UNIPO_R03_RUN_ID"
compose=("${ops_docker[@]}" compose --env-file /dev/null -f "$fixture_file" -p "$project")
artifact_dir="$task_root/.logs/r04/$UNIPO_R03_RUN_ID"
runtime_dir="$artifact_dir/runtime"
mkdir -p "$runtime_dir" "$artifact_dir/backups"
chmod 700 "$artifact_dir"

started=0
cleanup() {
  local result=$?
  trap - EXIT
  rm -rf -- "$runtime_dir"
  if [ "$started" = 1 ]; then
    for name in "$UNIPO_R04_SOURCE_MYSQL" "$UNIPO_R04_TARGET_MYSQL" "$UNIPO_R04_SOURCE_MINIO" "$UNIPO_R04_TARGET_MINIO"; do
      owner="$("${ops_docker[@]}" container inspect "$name" \
        --format '{{index .Config.Labels "com.unipo.fixture.run"}}' 2>/dev/null || true)"
      if [ -n "$owner" ] && [ "$owner" != "$UNIPO_R03_RUN_ID" ]; then
        printf 'Fixture ownership changed; refusing cleanup.\n' >&2
        exit 2
      fi
    done
    "${compose[@]}" down --timeout 10 >/dev/null 2>&1 || result=2
  fi
  unset UNIPO_R03_SOURCE_PASSWORD UNIPO_R04_SOURCE_ROOT_PASSWORD UNIPO_R04_TARGET_ROOT_PASSWORD UNIPO_R04_MINIO_PASSWORD
  if [ "$result" = 0 ]; then
    printf 'R04 drill %s passed; containers removed. Evidence: %s\n' "$UNIPO_R03_RUN_ID" "$artifact_dir"
  else
    printf 'R04 drill %s FAILED (exit %s); containers removed. Evidence: %s\n' "$UNIPO_R03_RUN_ID" "$result" "$artifact_dir" >&2
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

step() { printf '\n== %s\n' "$*"; }
require_equal() {
  [ "$2" = "$3" ] || { printf 'Check failed: %s (expected %s, got %s)\n' "$1" "$3" "$2" >&2; exit 1; }
}
expect_status() {
  local label="$1" want="$2" phrase="$3" log="$artifact_dir/$1.log" status
  shift 3
  set +e; "$@" >"$log" 2>&1; status=$?; set -e
  if [ "$status" != "$want" ] || ! grep -q -- "$phrase" "$log"; then
    printf 'Expected %s to exit %s with "%s" (got %s); see %s\n' "$label" "$want" "$phrase" "$status" "$log" >&2
    exit 1
  fi
  printf 'ok: %s (exit %s)\n' "$label" "$status"
  printf '%s exit=%s\n' "$label" "$status" >>"$artifact_dir/summary.txt"
}
src_mc() { "${ops_docker[@]}" exec "$UNIPO_R04_SOURCE_MINIO" mc "$@"; }
dst_mc() { "${ops_docker[@]}" exec "$UNIPO_R04_TARGET_MINIO" mc "$@"; }

step "Run $UNIPO_R03_RUN_ID: starting source/target MySQL and MinIO fixtures"
started=1
"${compose[@]}" up --detach --no-build --pull never
for attempt in {1..120}; do
  # shellcheck disable=SC2016
  if "${ops_docker[@]}" exec "$UNIPO_R04_SOURCE_MYSQL" sh -eu -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=TCP --host=127.0.0.1 \
        --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch --skip-column-names --execute="SELECT 1"' >/dev/null 2>&1 \
    && "${ops_docker[@]}" exec "$UNIPO_R04_TARGET_MYSQL" sh -eu -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --protocol=TCP \
        --host=127.0.0.1 --user=root --batch --skip-column-names --execute="SELECT 1"' >/dev/null 2>&1 \
    && src_mc stat "local/$UNIPO_R04_SOURCE_BUCKET" >/dev/null 2>&1 \
    && dst_mc ls local >/dev/null 2>&1; then
    break
  fi
  [ "$attempt" != 120 ] || { printf 'Fixtures did not become ready.\n' >&2; exit 1; }
  sleep 1
done
printf 'ready after %s polls\n' "$attempt"

step "Migrating V1-V$UNIPO_R03_EXPECTED_VERSION and seeding users"
# The shared Flyway fixture only migrates a schema that holds exactly this run's marker.
# shellcheck disable=SC2016
"${ops_docker[@]}" exec -e UNIPO_R03_RUN_ID "$UNIPO_R04_SOURCE_MYSQL" sh -eu -c '
  MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=socket --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --batch \
    --execute="CREATE TABLE r03_fixture_marker (run_id BINARY(8) PRIMARY KEY);
      INSERT INTO r03_fixture_marker (run_id) VALUES (0x$UNIPO_R03_RUN_ID);"'
unzip -q "$jar" 'BOOT-INF/lib/flyway-*' 'BOOT-INF/lib/mysql-connector-j-*' 'BOOT-INF/lib/jackson-*' \
  'BOOT-INF/lib/gson-*' 'BOOT-INF/classes/db/migration/*' -d "$runtime_dir"
UNIPO_R03_PORT=22317 UNIPO_R03_USER=unipo_fixture java -cp "$runtime_dir/BOOT-INF/classes:$runtime_dir/BOOT-INF/lib/*" \
  "$task_root/scripts/integration/FixtureFlywayMigrate.java" >"$artifact_dir/flyway.log" 2>&1
# shellcheck disable=SC2016
"${ops_docker[@]}" exec -i "$UNIPO_R04_SOURCE_MYSQL" sh -eu -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --protocol=socket \
  --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --default-character-set=utf8mb4' \
  <"$task_root/scripts/integration/mysql-backup-restore-seed.sql"

step "Uploading five image objects and recording four of them"
# Real JPEG/PNG headers followed by random bytes; one key has a space, one object stays unrecorded (orphan).
keys=("post/1/r04-a.jpg" "post/1/r04-b.png" "avatar/2/r04 c.jpg" "background/2/r04-d.jpg" "post/9/r04-orphan.jpg")
mkdir -p "$runtime_dir/objects"
for index in "${!keys[@]}"; do
  file="$runtime_dir/objects/$index"
  if [ "$index" = 1 ]; then printf '\x89PNG\r\n\x1a\n' >"$file"; else printf '\xff\xd8\xff\xe0' >"$file"; fi
  openssl rand $((2048 + index * 997)) >>"$file"
  chmod 644 "$file"  # docker cp keeps the mode; MinIO runs as another uid
  "${ops_docker[@]}" cp "$file" "$UNIPO_R04_SOURCE_MINIO:/tmp/r04-$index"
  src_mc cp --quiet "/tmp/r04-$index" "local/$UNIPO_R04_SOURCE_BUCKET/${keys[$index]}" >/dev/null
done
seed_sql="SET @u1 = (SELECT id FROM users WHERE public_uid = 'R0300001');
SET @u2 = (SELECT id FROM users WHERE public_uid = 'R0300002');
SET @p1 = (SELECT id FROM posts ORDER BY id LIMIT 1);
INSERT INTO media_objects (user_id, bucket, object_key, url, content_type, size_bytes, purpose, created_at) VALUES
 (@u1, '$UNIPO_R04_SOURCE_BUCKET', '${keys[0]}', 'pending', 'image/jpeg', 1, 'post', NOW()),
 (@u1, '$UNIPO_R04_SOURCE_BUCKET', '${keys[1]}', 'pending', 'image/png', 1, 'post', NOW()),
 (@u2, '$UNIPO_R04_SOURCE_BUCKET', '${keys[2]}', 'pending', 'image/jpeg', 1, 'avatar', NOW()),
 (@u2, '$UNIPO_R04_SOURCE_BUCKET', '${keys[3]}', 'pending', 'image/jpeg', 1, 'background', NOW());
UPDATE media_objects SET url = CONCAT('/api/media/', id, '/content?v=x');
INSERT INTO post_images (post_id, url, sort_order, created_at)
 SELECT @p1, url, id, NOW() FROM media_objects WHERE purpose = 'post';
UPDATE users SET avatar_url = (SELECT url FROM media_objects WHERE purpose = 'avatar') WHERE id = @u2;
UPDATE users SET background_url = 'https://cdn.unipo.invalid/$UNIPO_R04_SOURCE_BUCKET/${keys[3]}?v=1' WHERE id = @u2;
UPDATE users SET avatar_url = 'https://images.unsplash.com/photo-external.jpg' WHERE id = @u1;"
ops_credentials_init root ''
ops_sql "$UNIPO_R04_SOURCE_MYSQL" "USE \`$UNIPO_R03_SCHEMA\`; $seed_sql" >/dev/null

check() { bash "$ops/media-reference-check.sh" --mysql-container "$1" --database "$2" --minio-container "$3" --bucket "$4"; }

step "Source cross-check: nothing referenced is missing, one orphan"
expect_status source-check 0 'missing_media=0 missing_references=0 dangling_references=0 orphans=1' \
  check "$UNIPO_R04_SOURCE_MYSQL" "$UNIPO_R03_SCHEMA" "$UNIPO_R04_SOURCE_MINIO" "$UNIPO_R04_SOURCE_BUCKET"

step "Backing up the database, then the bucket"
UNIPO_BACKUP_DIR="$artifact_dir/backups" UNIPO_BACKUP_DATABASE="$UNIPO_R03_SCHEMA" UNIPO_BACKUP_CONTAINER="$UNIPO_R04_SOURCE_MYSQL" \
  bash "$ops/mysql-backup.sh" | tee -a "$artifact_dir/summary.txt"
UNIPO_MEDIA_BACKUP_DIR="$artifact_dir/backups" UNIPO_MEDIA_BUCKET="$UNIPO_R04_SOURCE_BUCKET" UNIPO_MEDIA_CONTAINER="$UNIPO_R04_SOURCE_MINIO" \
  bash "$ops/media-backup.sh" | tee -a "$artifact_dir/summary.txt"
dump="$(find "$artifact_dir/backups" -name "$UNIPO_R03_SCHEMA-*.sql.gz" | head -n 1)"
archive="$(find "$artifact_dir/backups" -name "$UNIPO_R04_SOURCE_BUCKET-*.tar.gz" | head -n 1)"
manifest="${archive%.tar.gz}.manifest.tsv"
require_equal 'manifest objects' "$(wc -l <"$manifest" | tr -d ' ')" 5
grep -q $'\tavatar/2/r04 c.jpg$' "$manifest" || { printf 'Key with a space missing from manifest\n' >&2; exit 1; }
if gzip -dc "$archive" | strings | grep -q "$UNIPO_R04_MINIO_PASSWORD"; then
  printf 'Archive contains the MinIO password\n' >&2; exit 1
fi

step "Refusals before anything is restored"
mkdir -p "$runtime_dir/tampered"
cp "$archive" "$archive.sha256" "$manifest" "$runtime_dir/tampered/"
printf 'x' >>"$runtime_dir/tampered/${archive##*/}"
expect_status restore-checksum 2 'Archive checksum mismatch' bash "$ops/media-restore.sh" \
  --archive "$runtime_dir/tampered/${archive##*/}" --container "$UNIPO_R04_TARGET_MINIO" --bucket "$target_bucket"
expect_status restore-production-name 2 'looks like production' bash "$ops/media-restore.sh" \
  --archive "$archive" --container "$UNIPO_R04_TARGET_MINIO" --bucket campuspo
expect_status restore-production-pattern 2 'looks like production' bash "$ops/media-restore.sh" \
  --archive "$archive" --container "$UNIPO_R04_TARGET_MINIO" --bucket media-prod-copy
expect_status restore-nonempty-bucket 2 'not empty' bash "$ops/media-restore.sh" \
  --archive "$archive" --container "$UNIPO_R04_SOURCE_MINIO" --bucket "$UNIPO_R04_SOURCE_BUCKET"

step "Restoring the database and the bucket into fresh containers"
bash "$ops/mysql-restore.sh" --file "$dump" --container "$UNIPO_R04_TARGET_MYSQL" --database "$restore_database" \
  --create-database >"$artifact_dir/mysql-restore.log" 2>&1
# The restored rows still name the source bucket; point them at the restored one as an operator would.
ops_sql "$UNIPO_R04_TARGET_MYSQL" "UPDATE \`$restore_database\`.media_objects SET bucket = '$target_bucket';
  UPDATE \`$restore_database\`.users SET background_url = REPLACE(background_url, '/$UNIPO_R04_SOURCE_BUCKET/', '/$target_bucket/')" >/dev/null
bash "$ops/media-restore.sh" --archive "$archive" --container "$UNIPO_R04_TARGET_MINIO" --bucket "$target_bucket" \
  | tee -a "$artifact_dir/summary.txt"

step "Restored images are byte-identical"
for index in "${!keys[@]}"; do
  dst_mc cp --quiet "local/$target_bucket/${keys[$index]}" "/tmp/back-$index" >/dev/null
  "${ops_docker[@]}" cp "$UNIPO_R04_TARGET_MINIO:/tmp/back-$index" "$runtime_dir/back-$index"
  require_equal "object ${keys[$index]}" "$(ops_sha256 "$runtime_dir/back-$index")" "$(ops_sha256 "$runtime_dir/objects/$index")"
done
printf 'all %s objects match\n' "${#keys[@]}"

step "Restored cross-check passes, then a deleted object is reported"
expect_status restored-check 0 'missing_media=0 missing_references=0 dangling_references=0 orphans=1' \
  check "$UNIPO_R04_TARGET_MYSQL" "$restore_database" "$UNIPO_R04_TARGET_MINIO" "$target_bucket"
dst_mc rm --quiet "local/$target_bucket/${keys[0]}" >/dev/null
dst_mc rm --quiet "local/$target_bucket/${keys[3]}" >/dev/null
expect_status detects-missing 3 'missing_media=2 missing_references=2 dangling_references=0' \
  check "$UNIPO_R04_TARGET_MYSQL" "$restore_database" "$UNIPO_R04_TARGET_MINIO" "$target_bucket"
grep -q "users.background_url" "$artifact_dir/detects-missing.log" || { printf 'Public-style reference not reported\n' >&2; exit 1; }
ops_sql "$UNIPO_R04_TARGET_MYSQL" "DELETE FROM \`$restore_database\`.media_objects WHERE object_key = '${keys[1]}'" >/dev/null
expect_status detects-dangling 3 'dangling_references=1' \
  check "$UNIPO_R04_TARGET_MYSQL" "$restore_database" "$UNIPO_R04_TARGET_MINIO" "$target_bucket"
