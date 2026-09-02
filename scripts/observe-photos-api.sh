#!/usr/bin/env bash
#
# Plan 001: answer plan.md §11 U1-U7 against a live Synology Photos.
#
# Reads nothing but read endpoints. Calls no write, rename, delete, upload or share
# method, and calls no method whose effect is unknown.
#
# Deliberately no `set -x`: the trace would contain the password.
#
# Secrets handling:
#   * the password is read with `read -s`, URL-encoded through a pipe, and sent to curl
#     on stdin, so it never appears in the process table or in the shell history;
#   * every response is redacted by key name before it is written to disk;
#   * the output directory is gitignored (documents/research/observation-*).
#
# Usage:  scripts/observe-photos-api.sh
#         scripts/observe-photos-api.sh https://nas.example.com:5001

set -u
set -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/documents/research/observation-$(date +%Y-%m-%d-%H%M%S)"
SUMMARY="$OUT/SUMMARY.txt"

BASE="${1:-}"
if [[ -z "$BASE" ]]; then
    read -r -p "NAS base URL (https://host:port): " BASE
fi
BASE="${BASE%/}"
if [[ "$BASE" != https://* ]]; then
    echo "Refusing a non-HTTPS base URL: the password would cross the network in the clear." >&2
    echo "Use the DDNS hostname with its certificate (decision 004)." >&2
    exit 2
fi

read -r -p "Account: " ACCOUNT
read -r -s -p "Password: " PASSWORD; echo
read -r -p "Two-factor code (blank if none): " OTP

mkdir -p "$OUT"
chmod 700 "$OUT"

note() { printf '%s\n' "$*" | tee -a "$SUMMARY"; }

urlencode() {
    python3 -c 'import sys,urllib.parse; sys.stdout.write(urllib.parse.quote(sys.stdin.read(), safe=""))'
}

# Replace the value of any sensitive key, at any depth, before anything reaches disk.
# Keyed on names because the full set of fields Photos returns is not known; the
# gitignore is the guarantee, this is the courtesy.
redact() {
    python3 <<'PY'
import json, sys

SECRET = {
    "sid", "synotoken", "SynoToken", "did", "device_id", "passwd", "password",
    "passphrase", "passphrase_share", "sharing_link", "hashed_password", "token",
}

def walk(node):
    if isinstance(node, dict):
        return {k: ("<redacted>" if k in SECRET else walk(v)) for k, v in node.items()}
    if isinstance(node, list):
        return [walk(v) for v in node]
    return node

raw = sys.stdin.read()
try:
    sys.stdout.write(json.dumps(walk(json.loads(raw)), indent=2, ensure_ascii=False) + "\n")
except json.JSONDecodeError:
    sys.stdout.write("<not JSON, %d bytes, withheld>\n" % len(raw))
PY
}

# api_call <name> <body>   Body goes over stdin so it is never a process argument.
api_call() {
    local name="$1" body="$2" out="$OUT/$name.json"
    printf '%s' "$body" \
        | curl -sS -X POST "$BASE/webapi/entry.cgi" \
               --data @- \
               "${CURL_HEADERS[@]}" \
        | redact > "$out"
    local verdict
    verdict=$(python3 - "$out" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    print("unparseable"); raise SystemExit
if d.get("success"):
    print("ok")
else:
    print("error %s" % d.get("error", {}).get("code", "?"))
PY
)
    note "  $name: $verdict"
}

note "# Photos web API observation"
note "# $(date -Iseconds)  base=$BASE  account=$ACCOUNT"
note ""

# ---- U1: what exists, and at which versions ----------------------------------
note "U1 SYNO.API.Info"
SYNOTOKEN=""
CURL_HEADERS=()
api_call api-info 'api=SYNO.API.Info&version=1&method=query&query=all'

python3 - "$OUT/api-info.json" > "$OUT/api-versions.txt" <<'PY'
import json, sys
d = json.load(open(sys.argv[1])).get("data", {})
keep = ("SYNO.API.Auth", "SYNO.Foto.", "SYNO.FotoTeam.")
for name in sorted(k for k in d if k.startswith(keep)):
    e = d[name]
    print(f"{name:55s} v{e.get('minVersion')}-{e.get('maxVersion')}  {e.get('path')}")
PY
note "  api-versions.txt: $(wc -l < "$OUT/api-versions.txt") apis this app might touch"
note ""

# ---- Sign in -----------------------------------------------------------------
# One attempt only. DSM auto-block bans the address after a few failures, and a
# retry loop here would lock the household out of its own NAS.
note "Sign in (one attempt, never retried)"
ACCOUNT_ENC=$(printf '%s' "$ACCOUNT" | urlencode)
PASSWORD_ENC=$(printf '%s' "$PASSWORD" | urlencode)
LOGIN_BODY="api=SYNO.API.Auth&version=7&method=login&account=$ACCOUNT_ENC&passwd=$PASSWORD_ENC"
LOGIN_BODY+="&format=sid&enable_syno_token=yes&enable_device_token=yes&device_name=onthisday-observe"
if [[ -n "$OTP" ]]; then
    LOGIN_BODY+="&otp_code=$(printf '%s' "$OTP" | urlencode)"
fi

LOGIN_RAW=$(printf '%s' "$LOGIN_BODY" | curl -sS -X POST "$BASE/webapi/entry.cgi" --data @-)
unset PASSWORD PASSWORD_ENC LOGIN_BODY

# Over stdin, not interpolated into the Python source: a body carrying a quote
# sequence would otherwise break the parse.
read -r SID SYNOTOKEN DID <<<"$(printf '%s' "$LOGIN_RAW" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    d = {}
data = d.get("data", {}) if d.get("success") else {}
print(data.get("sid", ""), data.get("synotoken", ""), data.get("did", ""))
')" || true

printf '%s' "$LOGIN_RAW" | redact > "$OUT/login.json"
unset LOGIN_RAW

if [[ -z "${SID:-}" ]]; then
    note "  FAILED. See login.json for the error code. Not retrying."
    note "  400 wrong credentials, 403 two-factor required, 404 wrong code, 407 address blocked."
    exit 1
fi
note "  ok. session established, device token ${DID:+received}${DID:-not received}"
if [[ -n "${SYNOTOKEN:-}" ]]; then
    CURL_HEADERS=(-H "X-SYNO-TOKEN: $SYNOTOKEN")
fi
note ""

cleanup() {
    if [[ -n "${SID:-}" ]]; then
        printf '%s' "api=SYNO.API.Auth&version=7&method=logout&_sid=$SID" \
            | curl -sS -X POST "$BASE/webapi/entry.cgi" --data @- >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

AUTH="_sid=$SID"

# ---- U2, U5, U7: the timeline -------------------------------------------------
# The histogram decision 005 is built on. Probing rather than assuming: the method
# name and version are not documented anywhere.
note "U2/U7 timeline"
for ns in SYNO.Foto SYNO.FotoTeam; do
    for v in 3 4 5 6; do
        api_call "timeline-${ns##*.}-v$v" \
            "api=$ns.Browse.Timeline&version=$v&method=get&$AUTH"
    done
done
note ""

# ---- U3, U5: the item list, and whether it takes a time range -----------------
note "U3/U5 item list"
for ns in SYNO.Foto SYNO.FotoTeam; do
    short="${ns##*.}"
    api_call "item-list-$short" \
        "api=$ns.Browse.Item&version=7&method=list&offset=0&limit=2&sort_by=takentime&sort_direction=desc&additional=%5B%22thumbnail%22%2C%22resolution%22%2C%22orientation%22%5D&$AUTH"
    api_call "item-count-$short" \
        "api=$ns.Browse.Item&version=7&method=count&$AUTH"
    # Two spellings seen in the wild. An error 120 (bad parameter) is the useful answer.
    api_call "item-timerange-a-$short" \
        "api=$ns.Browse.Item&version=7&method=list&offset=0&limit=2&start_time=1000000000&end_time=1999999999&$AUTH"
    api_call "item-timerange-b-$short" \
        "api=$ns.Browse.Item&version=7&method=list&offset=0&limit=2&time_start=1000000000&time_end=1999999999&$AUTH"
done

python3 - "$OUT" > "$OUT/takentime.txt" <<'PY'
import glob, json, os, sys, datetime
out = sys.argv[1]
for path in sorted(glob.glob(os.path.join(out, "item-list-*.json"))):
    try:
        items = json.load(open(path)).get("data", {}).get("list", [])
    except Exception:
        continue
    for it in items[:2]:
        t = it.get("time") or it.get("takentime")
        if not isinstance(t, int):
            continue
        as_s = datetime.datetime.utcfromtimestamp(t).isoformat()
        as_ms = datetime.datetime.utcfromtimestamp(t / 1000).isoformat()
        unit = "seconds" if 1970 < int(as_s[:4]) < 2100 else "milliseconds"
        print(f"{os.path.basename(path)}: raw={t} -> as seconds {as_s} / as ms {as_ms} => {unit}")
PY
note "  takentime.txt written (U5)"
note ""

# ---- U4: does the thumbnail serve bytes over a plain GET ---------------------
# Coil needs a GET URL. Only the status line and content type are recorded: the
# body is somebody's photograph.
note "U4 thumbnail"
read -r UNIT_ID CACHE_KEY <<<"$(python3 - "$OUT" <<'PY'
import glob, json, os, sys
for path in sorted(glob.glob(os.path.join(sys.argv[1], "item-list-*.json"))):
    try:
        items = json.load(open(path)).get("data", {}).get("list", [])
    except Exception:
        continue
    for it in items:
        ck = (it.get("additional") or {}).get("thumbnail", {}).get("cache_key")
        if ck:
            print(it.get("id"), ck)
            raise SystemExit
print("", "")
PY
)" || true

if [[ -n "${CACHE_KEY:-}" ]]; then
    for size in sm m xl; do
        code=$(curl -sS -o /dev/null -w '%{http_code} %{content_type} %{size_download}' \
            -G "$BASE/webapi/entry.cgi" \
            --data-urlencode "api=SYNO.Foto.Thumbnail" \
            --data-urlencode "version=2" \
            --data-urlencode "method=get" \
            --data-urlencode "id=$UNIT_ID" \
            --data-urlencode "cache_key=$CACHE_KEY" \
            --data-urlencode "type=unit" \
            --data-urlencode "size=$size" \
            --data-urlencode "_sid=$SID" 2>&1)
        note "  GET thumbnail size=$size -> $code"
    done
else
    note "  skipped: no cache_key in the item list, so U4 needs a manual look"
fi
note ""

note "Done. Output in $OUT"
note "Nothing here is committed; write findings into documents/research/photos-web-api.md."
