#!/usr/bin/env python3
"""Reduce one observation directory to the answers plan.md §11 asks for.

Runs at the end of observe-photos-api.sh and can be rerun on a kept directory at any time:

    scripts/summarise-observation.py documents/research/observation-<stamp>/

Reads only the redacted captures. Prints field names, counts, ids and dates; never a filename,
never the value of a redacted key. The timeline shape is unknown until observed, so the day
buckets are located by looking for year/month/day keys rather than by a fixed path.
"""

import datetime as dt
import glob
import json
import os
import sys
import zoneinfo

COUNT_KEYS = ("item_count", "count", "total", "num")
TIME_KEYS = ("time", "takentime", "taken_time")


def load(path):
    try:
        with open(path) as f:
            return json.load(f)
    except (OSError, json.JSONDecodeError):
        return None


def status(doc):
    if doc is None:
        return "unparseable"
    if doc.get("success"):
        return "ok"
    return "error %s" % doc.get("error", {}).get("code", "?")


def namespace_of(filename):
    """timeline-Foto-v1.json -> Foto; item-list-FotoTeam.json -> FotoTeam."""
    stem = os.path.splitext(os.path.basename(filename))[0]
    for part in stem.split("-"):
        if part in ("Foto", "FotoTeam"):
            return part
    return "?"


def find_day_list(node):
    """First list of dicts carrying year and month keys, at any depth."""
    if isinstance(node, list) and node and all(isinstance(x, dict) for x in node):
        if {"year", "month"} <= set(node[0]):
            return node
    children = node.values() if isinstance(node, dict) else node if isinstance(node, list) else ()
    for child in children:
        found = find_day_list(child)
        if found:
            return found
    return None


def count_key(bucket):
    for k in COUNT_KEYS:
        if isinstance(bucket.get(k), int):
            return k
    for k, v in bucket.items():
        if isinstance(v, int) and k not in ("year", "month", "day"):
            return k
    return None


def buckets_of(doc):
    """(entries, count_key, original_order) where entries are (y, m, d|None, n)."""
    raw = find_day_list(doc.get("data", doc)) if doc else None
    if not raw:
        return None, None, None
    ck = count_key(raw[0])
    if ck is None:
        return None, None, None
    entries = [(b.get("year"), b.get("month"), b.get("day"), b.get(ck, 0)) for b in raw]
    keys = [e[:3] for e in entries]
    order = "desc" if keys == sorted(keys, reverse=True) else "asc" if keys == sorted(keys) else "unsorted"
    return entries, ck, order


def item_time(item):
    for k in TIME_KEYS:
        v = item.get(k)
        if isinstance(v, int):
            return k, v
    return None, None


def unit_of(t):
    return "milliseconds" if t > 10**11 else "seconds"


def as_seconds(t):
    return t / 1000 if unit_of(t) == "milliseconds" else t


def items_of(doc):
    if not doc or not doc.get("success"):
        return []
    data = doc.get("data", {})
    for k in ("list", "items"):
        if isinstance(data.get(k), list):
            return data[k]
    return []


def local_zone():
    name = os.environ.get("OTD_TZ")
    if name:
        return zoneinfo.ZoneInfo(name)
    return dt.datetime.now().astimezone().tzinfo


def dates_of(t, zone):
    s = as_seconds(t)
    utc = dt.datetime.fromtimestamp(s, dt.timezone.utc).date()
    loc = dt.datetime.fromtimestamp(s, zone).date()
    return utc, loc


# ---- sections ------------------------------------------------------------------


def section_u1(out):
    doc = load(os.path.join(out, "api-info.json"))
    print("U1 SYNO.API.Info:", status(doc))
    if not doc or not doc.get("success"):
        return
    apis = doc.get("data", {})
    keep = ("SYNO.API.Auth", "SYNO.Foto.", "SYNO.FotoTeam.")
    lines = []
    for name in sorted(k for k in apis if k.startswith(keep)):
        e = apis[name]
        lines.append(f"{name:55s} v{e.get('minVersion')}-{e.get('maxVersion')}  {e.get('path')}")
    with open(os.path.join(out, "api-versions.txt"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"  {len(lines)} apis this app might touch, listed in api-versions.txt")
    for name in ("SYNO.API.Auth", "SYNO.Foto.Browse.Timeline", "SYNO.FotoTeam.Browse.Timeline",
                 "SYNO.Foto.Browse.Item", "SYNO.FotoTeam.Browse.Item",
                 "SYNO.Foto.Thumbnail", "SYNO.FotoTeam.Thumbnail"):
        e = apis.get(name)
        print(f"  {name:40s}", f"v{e.get('minVersion')}-{e.get('maxVersion')}" if e else "ABSENT")


def section_u2(out):
    print("U2 timeline")
    best = {}
    for path in sorted(glob.glob(os.path.join(out, "timeline-*.json"))):
        doc = load(path)
        name = os.path.basename(path).removesuffix(".json")
        st = status(doc)
        if st != "ok":
            print(f"  {name}: {st}")
            continue
        entries, ck, order = buckets_of(doc)
        if entries is None:
            print(f"  {name}: ok, but no year/month buckets found. data keys: {sorted(doc.get('data', {}))}")
            continue
        has_day = any(e[2] is not None for e in entries)
        newest = max(entries)[:3]
        oldest = min(entries)[:3]
        print(f"  {name}: ok, {len(entries)} buckets, {'day' if has_day else 'month'} granularity,"
              f" count key '{ck}', order {order}, bucket keys {sorted(find_day_list(doc['data'])[0])},"
              f" span {oldest} .. {newest}, total {sum(e[3] for e in entries)}")
        ns = namespace_of(path)
        if has_day and ns not in best:
            best[ns] = entries
    return best


def section_u3(out):
    print("U3 item list and time range")
    for ns in ("Foto", "FotoTeam"):
        base = load(os.path.join(out, f"item-list-{ns}.json"))
        items = items_of(base)
        st = status(base)
        if items:
            first = items[0]
            extra = sorted((first.get("additional") or {}).keys())
            print(f"  item-list-{ns}: {st}, {len(items)} items, item keys {sorted(first)}, additional {extra}")
        else:
            print(f"  item-list-{ns}: {st}, no items")
        cnt = load(os.path.join(out, f"item-count-{ns}.json"))
        print(f"  item-count-{ns}: {status(cnt)}", (cnt or {}).get("data") if cnt and cnt.get("success") else "")
        base_ids = [i.get("id") for i in items[:2]]
        for variant in ("a", "b"):
            path = os.path.join(out, f"item-timerange-{variant}-{ns}.json")
            if not os.path.exists(path):
                continue
            doc = load(path)
            st = status(doc)
            if st != "ok":
                verdict = f"{st} (rejected)"
            else:
                got = [i.get("id") for i in items_of(doc)]
                if items and got == base_ids:
                    verdict = "ok, same two items as unfiltered: parameter IGNORED"
                else:
                    verdict = f"ok, {len(got)} items, differs from unfiltered: parameter HONOURED"
            spelling = "start_time/end_time" if variant == "a" else "time_start/time_end"
            print(f"  timerange {spelling} on {ns}: {verdict}")


def section_u5(out):
    print("U5 taken time unit")
    seen = False
    for ns in ("Foto", "FotoTeam"):
        for it in items_of(load(os.path.join(out, f"item-list-{ns}.json")))[:2]:
            k, t = item_time(it)
            if t is None:
                continue
            seen = True
            iso = dt.datetime.fromtimestamp(as_seconds(t), dt.timezone.utc).isoformat()
            print(f"  {ns} field '{k}' raw={t} -> {iso} UTC => {unit_of(t)}")
    if not seen:
        print("  no integer time field found on the first items; keys are printed under U3")


def section_u7(out, timelines):
    zone = local_zone()
    print(f"U7 day boundaries: UTC or local ({zone})")
    for ns, entries in timelines.items():
        items = items_of(load(os.path.join(out, f"item-list-{ns}.json")))
        times = [t for t in (item_time(i)[1] for i in items) if isinstance(t, int)]
        if len(times) < 2:
            print(f"  {ns}: too few items to compare")
            continue
        utc_count, loc_count = {}, {}
        for t in times:
            u, l = dates_of(t, zone)
            utc_count[u] = utc_count.get(u, 0) + 1
            loc_count[l] = loc_count.get(l, 0) + 1
        # The oldest item in the window is on a day the window covers only partly.
        oldest_utc, oldest_loc = dates_of(min(times), zone)
        timeline = {dt.date(y, m, d): n for (y, m, d, n) in entries if d}
        rows, utc_ok, loc_ok = [], 0, 0
        for day in sorted(set(utc_count) | set(loc_count), reverse=True):
            if day <= max(oldest_utc, oldest_loc):
                continue
            tl = timeline.get(day)
            u, l = utc_count.get(day, 0), loc_count.get(day, 0)
            utc_ok += tl == u
            loc_ok += tl == l
            rows.append(f"    {day}  timeline={tl}  utc={u}  local={l}")
        if not rows:
            print(f"  {ns}: the item window spans one day; nothing to compare")
            continue
        n = len(rows)
        if utc_ok == loc_ok:
            verdict = "indistinguishable in this window: no photo near midnight"
        elif loc_ok > utc_ok:
            verdict = f"LOCAL ({zone}): {loc_ok}/{n} days match, UTC {utc_ok}/{n}"
        else:
            verdict = f"UTC: {utc_ok}/{n} days match, local {loc_ok}/{n}"
        print(f"  {ns}: {verdict}")
        print("\n".join(rows))


def section_offset(out):
    """Decision 005's arithmetic, checked once against the live list."""
    zone = local_zone()
    print("Offset verification (decision 005)")
    found = False
    for path in sorted(glob.glob(os.path.join(out, "offset-target-*.txt"))):
        found = True
        ns = namespace_of(path)
        with open(path) as f:
            offset, count, y, m, d = (int(x) for x in f.read().split())
        target = dt.date(y, m, d)
        doc = load(os.path.join(out, f"item-offset-{ns}.json"))
        st = status(doc)
        items = items_of(doc)
        print(f"  {ns}: day {target} count {count}, fetched offset={max(offset - 1, 0)} limit={count + 2}: {st}, {len(items)} items")
        if not items:
            continue
        marks = []
        for it in items:
            _, t = item_time(it)
            if t is None:
                marks.append("?")
                continue
            u, l = dates_of(t, zone)
            marks.append(("L" if l == target else "l") + ("U" if u == target else "u"))
        print("    per item, L/U = local/UTC date equals target, lower case = does not:", " ".join(marks))
        inside_local = sum(1 for x in marks if x.startswith("L"))
        inside_utc = sum(1 for x in marks if x.endswith("U"))
        expect_edges = "" if offset == 0 else " (one item before and one after are expected)"
        print(f"    items on the target day: local {inside_local}, UTC {inside_utc}, timeline says {count}{expect_edges}")
    if not found:
        print("  skipped: no offset target was written (timeline shape not recognised during the run)")


def pick_offset_target(out, ns):
    """Used mid-run: choose a recent day and print 'offset count year month day' for it.

    The third most recent day, so the offset is non-zero and small: the check then costs one
    cheap call and still exercises the running-total arithmetic. Prints nothing when no
    day-granular timeline for the namespace was captured, and the shell skips the check.
    """
    for path in sorted(glob.glob(os.path.join(out, f"timeline-{ns}-*.json"))):
        entries, _, _ = buckets_of(load(path))
        if not entries or not all(e[2] is not None for e in entries):
            continue
        entries = sorted(entries, reverse=True)
        idx = min(2, len(entries) - 1)
        y, m, d, n = entries[idx]
        print(sum(e[3] for e in entries[:idx]), n, y, m, d)
        return
    print("")


def main():
    if len(sys.argv) == 4 and sys.argv[1] == "--pick-offset":
        pick_offset_target(sys.argv[2], sys.argv[3])
        return
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        print(__doc__)
        sys.exit(2)
    out = sys.argv[1]
    print("=" * 72)
    print("Summary of", out)
    section_u1(out)
    print()
    timelines = section_u2(out)
    print()
    section_u3(out)
    print()
    section_u5(out)
    print()
    section_u7(out, timelines or {})
    print()
    section_offset(out)
    print("=" * 72)


if __name__ == "__main__":
    main()
