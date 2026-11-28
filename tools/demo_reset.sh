#!/usr/bin/env bash
# Demo reset (T4): empty the v2 demo data in hadaq_db and the uploaded capture files. Stop the core API first.
#   bash tools/demo_reset.sh        (or make demo-reset; on Windows run it in Git Bash)
# Then start the core API again (make core): it reseeds the first survey.
# With the core API running instead: curl -X POST localhost:8080/api/v2/demo/reset
set -euo pipefail
cd "$(dirname "$0")/.."

# The MySQL/MariaDB client: $MYSQL, else mysql on the PATH, else XAMPP's (Windows, then macOS)
if [ -z "${MYSQL:-}" ]; then
  if command -v mysql >/dev/null 2>&1; then MYSQL=mysql
  elif [ -x /c/xampp/mysql/bin/mysql.exe ]; then MYSQL=/c/xampp/mysql/bin/mysql.exe
  elif [ -x /Applications/XAMPP/xamppfiles/bin/mysql ]; then MYSQL=/Applications/XAMPP/xamppfiles/bin/mysql
  else echo "No mysql client found. Set MYSQL=/path/to/mysql" >&2; exit 1
  fi
fi

DB="${DB_NAME:-hadaq_db}"
"$MYSQL" -u "${DB_USER:-root}" ${DB_PASSWORD:+-p"$DB_PASSWORD"} "$DB" < tools/demo_reset.sql

# the uploaded photos and rectified images, in the core API's default folder (a custom CAPTURE_DIR: empty it yourself)
CAPTURES=services/core/data/captures
if [ -d "$CAPTURES" ]; then
  find "$CAPTURES" -mindepth 1 -maxdepth 1 -type f -delete
fi

echo "clean: $DB has no v2 demo data. Start the core API (make core) to reseed the survey."
