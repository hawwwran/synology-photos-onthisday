#!/usr/bin/env bash
#
# Plan 001: answer plan.md §11 U1-U7 against a live Synology Photos, and check decision 005's
# offset arithmetic once against the real list.
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
# Versions are taken from SYNO.API.Info rather than hard-coded, because a wrong version is
# error 104 and a wasted session. The fallbacks are what the companion repo observed on
# Photos 1.9.1.
#
# Usage:  scripts/observe-photos-api.sh
#         scripts/observe-photos-api.sh https://nas.example.com:5001
#
# For U6 (does SYNO.FotoTeam.* respect folder permissions), run it a second time as the
# restricted account and compare item-count-FotoTeam.json between the two directories.

set -u
set -o pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SUMMARISE="$REPO/scripts/summarise-observation.py"
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
received() { if [[ -n "$1" ]]; then echo received; else echo "not received"; fi; }

urlencode() {
    python3 -c 'import sys,urllib.parse; sys.stdout.write(urllib.parse.quote(sys.stdin.read(), safe=""))'
}

# Replace the value of any sensitive key, at any depth, before anything reaches disk.
# Keyed on names because the full set of fields Photos returns is not known; the
# gitignore is the guarantee, this is the courtesy.
# The code travels as an argument, not a heredoc: a heredoc would become python's stdin
# and the response on the pipe would never be read.
REDACT_PY=$(cat <<'PY'
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
)
redact() { python3 -c "$REDACT_PY"; }

CURL_HEADERS=()
VERDICT=""

# api_call <name> <body>   Body goes over stdin so it is never a process argument.
# Leaves the outcome in VERDICT so a caller can branch on it.
api_call() {
    local name="$1" body="$2"
    local out="$OUT/$name.json"
    printf '%s' "$body" \
        | curl -sS -X POST "$BASE/webapi/entry.cgi" \
               --data @- \
               "${CURL_HEADERS[@]}" \
        | redact > "$out"
    VERDICT=$(python3 - "$out" <<'PY'
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
    note "  $name: $VERDICT"
}

# apiver <api> <min|max> <fallback>   Version bound from the SYNO.API.Info dump.
apiver() {
    python3 - "$OUT/api-info.json" "$1" "$2" "$3" <<'PY'
import json, sys
path, api, bound, fallback = sys.argv[1:]
try:
    entry = json.load(open(path))["data"][api]
    print(int(entry["minVersion" if bound == "min" else "maxVersion"]))
except Exception:
    print(fallback)
PY
}

note "# Photos web API observation"
note "# $(date -Iseconds)  base=$BASE  account=$ACCOUNT"
note ""

# ---- U1: what exists, and at which versions ----------------------------------
note "U1 SYNO.API.Info"
api_call api-info 'api=SYNO.API.Info&version=1&method=query&query=all'
if [[ "$VERDICT" != ok ]]; then
    note "  Cannot reach the API. Stopping before the login attempt."
    exit 1
fi

V_AUTH=$(apiver SYNO.API.Auth max 7)
(( V_AUTH > 7 )) && V_AUTH=7    # the login parameters below are the v6/v7 set
V_ITEM=$(apiver SYNO.Foto.Browse.Item max 7)
V_ITEM_TEAM=$(apiver SYNO.FotoTeam.Browse.Item max "$V_ITEM")
V_THUMB=$(apiver SYNO.Foto.Thumbnail max 2)
V_THUMB_TEAM=$(apiver SYNO.FotoTeam.Thumbnail max "$V_THUMB")
note "  using Auth v$V_AUTH, Browse.Item v$V_ITEM/v$V_ITEM_TEAM, Thumbnail v$V_THUMB/v$V_THUMB_TEAM"
note ""

# ---- Sign in -----------------------------------------------------------------
# One attempt only. DSM auto-block bans the address after a few failures, and a
# retry loop here would lock the household out of its own NAS.
note "Sign in (one attempt, never retried)"
ACCOUNT_ENC=$(printf '%s' "$ACCOUNT" | urlencode)
PASSWORD_ENC=$(printf '%s' "$PASSWORD" | urlencode)
LOGIN_BODY="api=SYNO.API.Auth&version=$V_AUTH&method=login&account=$ACCOUNT_ENC&passwd=$PASSWORD_ENC"
LOGIN_BODY+="&format=sid&enable_syno_token=yes&enable_device_token=yes&device_name=onthisday-observe"
if [[ -n "$OTP" ]]; then
    LOGIN_BODY+="&otp_code=$(printf '%s' "$OTP" | urlencode)"
fi

LOGIN_RAW=$(printf '%s' "$LOGIN_BODY" | curl -sS -X POST "$BASE/webapi/entry.cgi" --data @-)
unset PASSWORD PASSWORD_ENC LOGIN_BODY

# Over stdin, not interpolated into the Python source: a body carrying a quote
# sequence would otherwise break the parse. One field per line so an absent
# synotoken cannot shift `did` into its place.
{ read -r SID; read -r SYNOTOKEN; read -r DID; } <<<"$(printf '%s' "$LOGIN_RAW" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    d = {}
data = d.get("data", {}) if d.get("success") else {}
for k in ("sid", "synotoken", "did"):
    print(data.get(k, "") or "-")
')" || true
[[ "${SID:-}" == "-" ]] && SID=""
[[ "${SYNOTOKEN:-}" == "-" ]] && SYNOTOKEN=""
[[ "${DID:-}" == "-" ]] && DID=""

printf '%s' "$LOGIN_RAW" | redact > "$OUT/login.json"
unset LOGIN_RAW

if [[ -z "${SID:-}" ]]; then
    note "  FAILED. See login.json for the error code. Not retrying."
    note "  400 wrong credentials, 403 two-factor required, 404 wrong code, 407 address blocked."
    exit 1
fi
note "  ok. session established, token $(received "${SYNOTOKEN:-}"), device token $(received "${DID:-}")"
if [[ -n "${SYNOTOKEN:-}" ]]; then
    CURL_HEADERS=(-H "X-SYNO-TOKEN: $SYNOTOKEN")
fi
note ""

cleanup() {
    if [[ -n "${SID:-}" ]]; then
        printf '%s' "api=SYNO.API.Auth&version=$V_AUTH&method=logout&_sid=$SID" \
            | curl -sS -X POST "$BASE/webapi/entry.cgi" --data @- >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT

AUTH="_sid=$SID"

# ---- U2, U7: the timeline -------------------------------------------------------
# The histogram decision 005 is built on. Every version SYNO.API.Info admits is probed,
# plain and with the grouping parameter the Photos web client is believed to send, so
# one run tells which combination this Photos answers.
note "U2/U7 timeline"
for ns in SYNO.Foto SYNO.FotoTeam; do
    short="${ns##*.}"
    vmin=$(apiver "$ns.Browse.Timeline" min 1)
    vmax=$(apiver "$ns.Browse.Timeline" max 6)
    (( vmax - vmin > 5 )) && vmin=$(( vmax - 5 ))
    for (( v = vmin; v <= vmax; v++ )); do
        api_call "timeline-$short-v$v" \
            "api=$ns.Browse.Timeline&version=$v&method=get&$AUTH"
        api_call "timeline-$short-v$v-day" \
            "api=$ns.Browse.Timeline&version=$v&method=get&timeline_group_unit=day&$AUTH"
    done
done
note ""

# ---- U3, U5: the item list, and whether it takes a time range -----------------
# limit=100 rather than 2: the summariser buckets these by UTC and by local date and
# compares against the timeline, which is what answers U7 without a second session.
note "U3/U5 item list"
ADDITIONAL='additional=%5B%22thumbnail%22%2C%22resolution%22%2C%22orientation%22%5D'
for ns in SYNO.Foto SYNO.FotoTeam; do
    short="${ns##*.}"
    v=$V_ITEM; [[ "$short" == FotoTeam ]] && v=$V_ITEM_TEAM
    SORT='sort_by=takentime&sort_direction=desc'
    api_call "item-list-$short" \
        "api=$ns.Browse.Item&version=$v&method=list&offset=0&limit=100&$SORT&$ADDITIONAL&$AUTH"
    if [[ "$VERDICT" == "error 120" ]]; then
        # The Photos web client JSON-quotes string values; some builds insist on it.
        SORT='sort_by=%22takentime%22&sort_direction=%22desc%22'
        note "  retrying with JSON-quoted sort parameters"
        api_call "item-list-$short" \
            "api=$ns.Browse.Item&version=$v&method=list&offset=0&limit=100&$SORT&$ADDITIONAL&$AUTH"
    fi
    api_call "item-count-$short" \
        "api=$ns.Browse.Item&version=$v&method=count&$AUTH"
    # Two spellings seen in the wild. The range is 2001-2004 so that a filter that is
    # honoured returns something other than the two newest items, and a filter that is
    # silently ignored is caught by the summariser as "same items as unfiltered".
    api_call "item-timerange-a-$short" \
        "api=$ns.Browse.Item&version=$v&method=list&offset=0&limit=2&$SORT&start_time=1000000000&end_time=1100000000&$AUTH"
    api_call "item-timerange-b-$short" \
        "api=$ns.Browse.Item&version=$v&method=list&offset=0&limit=2&$SORT&time_start=1000000000&time_end=1100000000&$AUTH"
done
note ""

# ---- Decision 005: does the running total really equal the offset -------------
# The picker chooses the third most recent day from the timeline just captured and
# prints its offset and count. Fetching one item either side is plan 004's overlap
# read, so the summariser can show the edges landing outside the day.
note "Offset verification"
for ns in SYNO.Foto SYNO.FotoTeam; do
    short="${ns##*.}"
    v=$V_ITEM; [[ "$short" == FotoTeam ]] && v=$V_ITEM_TEAM
    target=$(python3 "$SUMMARISE" --pick-offset "$OUT" "$short")
    if [[ -z "$target" ]]; then
        note "  $short: skipped, no day-granular timeline recognised"
        continue
    fi
    read -r off cnt y m d <<<"$target"
    printf '%s\n' "$target" > "$OUT/offset-target-$short.txt"
    from=$(( off > 0 ? off - 1 : 0 ))
    note "  $short: day $y-$m-$d has $cnt items at running total $off; fetching offset=$from limit=$(( cnt + 2 ))"
    api_call "item-offset-$short" \
        "api=$ns.Browse.Item&version=$v&method=list&offset=$from&limit=$(( cnt + 2 ))&$SORT&$AUTH"
done
note ""

# ---- U4: does the thumbnail serve bytes over a plain GET ---------------------
# Coil needs a GET URL. Only the status line and content type are recorded: the
# body is somebody's photograph. Tried with and without the X-SYNO-TOKEN header,
# because whether DSM demands the token on a sid-authenticated GET decides what
# the app's image loader has to attach.
note "U4 thumbnail"
{ read -r THUMB_NS; read -r UNIT_ID; read -r CACHE_KEY; } <<<"$(python3 - "$OUT" <<'PY'
import glob, json, os, sys
for path in sorted(glob.glob(os.path.join(sys.argv[1], "item-list-*.json"))):
    try:
        items = json.load(open(path)).get("data", {}).get("list", [])
    except Exception:
        continue
    for it in items:
        ck = (it.get("additional") or {}).get("thumbnail", {}).get("cache_key")
        if ck:
            ns = "SYNO.FotoTeam" if "FotoTeam" in os.path.basename(path) else "SYNO.Foto"
            print(ns); print(it.get("id")); print(ck)
            raise SystemExit
print("-"); print("-"); print("-")
PY
)" || true

if [[ -n "${CACHE_KEY:-}" && "$CACHE_KEY" != "-" ]]; then
    v=$V_THUMB; [[ "$THUMB_NS" == SYNO.FotoTeam ]] && v=$V_THUMB_TEAM
    note "  using a $THUMB_NS item, Thumbnail v$v"
    thumb_get() {
        local size="$1"; shift
        curl -sS -o /dev/null -w '%{http_code} %{content_type} %{size_download}B' \
            -G "$BASE/webapi/entry.cgi" "$@" \
            --data-urlencode "api=$THUMB_NS.Thumbnail" \
            --data-urlencode "version=$v" \
            --data-urlencode "method=get" \
            --data-urlencode "id=$UNIT_ID" \
            --data-urlencode "cache_key=$CACHE_KEY" \
            --data-urlencode "type=unit" \
            --data-urlencode "size=$size" \
            --data-urlencode "_sid=$SID" 2>&1
    }
    for size in sm m xl; do
        note "  GET size=$size, sid only          -> $(thumb_get "$size")"
        if [[ -n "${SYNOTOKEN:-}" ]]; then
            note "  GET size=$size, sid + X-SYNO-TOKEN -> $(thumb_get "$size" -H "X-SYNO-TOKEN: $SYNOTOKEN")"
        fi
    done
else
    note "  skipped: no cache_key in the item list, so U4 needs a manual look"
fi
note ""

python3 "$SUMMARISE" "$OUT" | tee -a "$SUMMARY"
note ""
note "Done. Output in $OUT"
note "Nothing here is committed; write findings into documents/research/photos-web-api.md."
