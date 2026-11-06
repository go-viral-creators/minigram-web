#!/bin/bash
# Uploads a folder to Hostinger through the file-manager TUS endpoint.
# usage: URL=... AUTH=... REST=... tools/hostinger_upload.sh <local-dir> <remote-dir-under-public_html>
# (get URL/AUTH/REST from the Hostinger MCP operation hosting_files_generate-upload-url; they expire after a few hours and are never stored)
set -e
SRC="$1"; DST="$2"
cd "$SRC"
find . -type f ! -name '.DS_Store' | sed 's|^\./||' | while read -r f; do
  S=$(stat -c%s "$f")
  c1=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$URL/$DST/$f?override=true" -H "X-Auth: $AUTH" -H "X-Auth-Rest: $REST" -H "Tus-Resumable: 1.0.0" -H "Upload-Length: $S" -H "Upload-Offset: 0")
  c2=$(curl -s -o /dev/null -w "%{http_code}" -X PATCH "$URL/$DST/$f?override=true" -H "X-Auth: $AUTH" -H "X-Auth-Rest: $REST" -H "Tus-Resumable: 1.0.0" -H "Content-Type: application/offset+octet-stream" -H "Upload-Offset: 0" --data-binary "@$f")
  echo "$c1 $c2 $f"
done
