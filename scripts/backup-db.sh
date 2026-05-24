#!/usr/bin/env bash
# Stocka — daily DB backup
#
# Dumps the MariaDB database from the docker container, gzips it, uploads to
# a Cloudflare R2 bucket and prunes local copies older than the configured
# retention (default: keep 7 most recent).
#
# Configuration (via env vars or sourced .env):
#   STOCKA_ENV_FILE       Path to .env to load (default: /opt/stocka/.env)
#   MARIADB_CONTAINER     Docker container name (default: stocka-bd)
#   BACKUP_DIR            Local backup directory (default: /var/backups/stocka)
#   LOCAL_RETENTION       How many local files to keep (default: 7)
#   R2_BACKUP_BUCKET      R2 bucket for backups (default: $R2_BUCKET)
#   R2_BACKUP_PREFIX      Object key prefix inside the bucket (default: db/)
#
# Required from .env (or already exported):
#   DB_NAME, DB_ROOT_PASSWORD
#   R2_ENDPOINT, R2_ACCESS_KEY, R2_SECRET_KEY, R2_BUCKET (or R2_BACKUP_BUCKET)
#
# Cron example (root):
#   15 3 * * * /opt/stocka/backup-db.sh >> /var/log/stocka-backup.log 2>&1

set -euo pipefail

ENV_FILE="${STOCKA_ENV_FILE:-/opt/stocka/.env}"
MARIADB_CONTAINER="${MARIADB_CONTAINER:-stocka-bd}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/stocka}"
LOCAL_RETENTION="${LOCAL_RETENTION:-7}"

log() { printf '[%s] %s\n' "$(date -Iseconds)" "$*"; }
die() { printf '[%s] ERROR: %s\n' "$(date -Iseconds)" "$*" >&2; exit 1; }

# Single-instance lock: skip silently if a previous run is still going.
LOCK_FILE="/var/lock/stocka-backup.lock"
exec 9>"$LOCK_FILE"
flock -n 9 || { log "Another backup is running, exiting."; exit 0; }

[[ -r "$ENV_FILE" ]] || die "Cannot read env file: $ENV_FILE"
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

: "${DB_NAME:?DB_NAME is required}"
: "${DB_ROOT_PASSWORD:?DB_ROOT_PASSWORD is required}"
: "${R2_ENDPOINT:?R2_ENDPOINT is required}"
: "${R2_ACCESS_KEY:?R2_ACCESS_KEY is required}"
: "${R2_SECRET_KEY:?R2_SECRET_KEY is required}"

R2_BACKUP_BUCKET="${R2_BACKUP_BUCKET:-${R2_BUCKET:-}}"
[[ -n "$R2_BACKUP_BUCKET" ]] || die "R2_BACKUP_BUCKET (or R2_BUCKET) is required"
R2_BACKUP_PREFIX="${R2_BACKUP_PREFIX:-db/}"
[[ "$R2_BACKUP_PREFIX" != */ ]] && R2_BACKUP_PREFIX="${R2_BACKUP_PREFIX}/"

command -v docker >/dev/null || die "docker not found in PATH"
command -v aws    >/dev/null || die "aws CLI not found in PATH (install awscli v2)"
docker inspect "$MARIADB_CONTAINER" >/dev/null 2>&1 \
  || die "Container '$MARIADB_CONTAINER' is not running"

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
FILE_NAME="${DB_NAME}-${TIMESTAMP}.sql.gz"
FILE_PATH="${BACKUP_DIR}/${FILE_NAME}"

log "Dumping ${DB_NAME} → ${FILE_PATH}"
# --single-transaction: consistent dump without locking InnoDB tables.
# --quick: stream rows instead of buffering whole tables in memory.
# --routines/--triggers/--events: include stored procs/triggers/scheduled events.
# MYSQL_PWD via env avoids leaking the password in the process list (`ps`).
docker exec -e MYSQL_PWD="$DB_ROOT_PASSWORD" "$MARIADB_CONTAINER" \
  mariadb-dump \
    -uroot \
    --single-transaction \
    --quick \
    --routines \
    --triggers \
    --events \
    --default-character-set=utf8mb4 \
    --databases "$DB_NAME" \
  | gzip -9 > "$FILE_PATH"

chmod 600 "$FILE_PATH"

[[ -s "$FILE_PATH" ]] || die "Dump file is empty: $FILE_PATH"
gzip -t "$FILE_PATH" 2>/dev/null || { rm -f "$FILE_PATH"; die "Dump file is corrupted"; }
SIZE_HUMAN="$(du -h "$FILE_PATH" | cut -f1)"
log "Dump OK (${SIZE_HUMAN})"

log "Uploading to r2://${R2_BACKUP_BUCKET}/${R2_BACKUP_PREFIX}${FILE_NAME}"
AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY" \
AWS_SECRET_ACCESS_KEY="$R2_SECRET_KEY" \
AWS_DEFAULT_REGION="auto" \
  aws s3 cp "$FILE_PATH" \
    "s3://${R2_BACKUP_BUCKET}/${R2_BACKUP_PREFIX}${FILE_NAME}" \
    --endpoint-url "$R2_ENDPOINT" \
    --only-show-errors \
  || die "Upload to R2 failed"
log "Upload OK"

# Local retention: keep the LOCAL_RETENTION most recent files, delete the rest.
# Using ls + tail is safe here because filenames are timestamped and contain
# no spaces/newlines.
mapfile -t TO_DELETE < <(
  ls -1t "${BACKUP_DIR}/${DB_NAME}-"*.sql.gz 2>/dev/null | tail -n +$((LOCAL_RETENTION + 1))
)
if (( ${#TO_DELETE[@]} > 0 )); then
  log "Pruning ${#TO_DELETE[@]} old local backup(s)"
  for f in "${TO_DELETE[@]}"; do
    log "  rm $f"
    rm -f "$f"
  done
fi

REMAINING="$(ls -1 "${BACKUP_DIR}/${DB_NAME}-"*.sql.gz 2>/dev/null | wc -l)"
log "Done. Local copies: ${REMAINING} (retention: ${LOCAL_RETENTION})"
