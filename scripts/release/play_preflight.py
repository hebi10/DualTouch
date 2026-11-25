"""WIF and Play inventory inspection; never upload, update tracks or commit an edit."""
import argparse
import os
import re
import urllib.parse

import release


def guard():
    sha = os.environ.get("EXPECTED_MAIN_SHA", "")
    release.require(os.environ.get("CHECK_APPROVED") == "true", "Explicit preflight approval is required.")
    release.require(os.environ.get("GITHUB_REPOSITORY") == release.REPO and os.environ.get("GITHUB_REF") == "refs/heads/main", "Preflight is restricted to DualTouch main.")
    release.require(re.fullmatch(r"[a-f0-9]{40}", sha) and sha == os.environ.get("GITHUB_SHA"), "Approved source differs from workflow source.")
    release.require(release.github("git/ref/heads/main")["object"]["sha"] == sha, "Main changed; review a new source.")
    return sha


def version_codes(items):
    values = []
    for item in items:
        value = str(item.get("versionCode", ""))
        release.require(re.fullmatch(r"[1-9][0-9]*", value), "Malformed Play binary inventory.")
        values.append(int(value))
    return values


def check():
    sha = guard()
    token = os.environ.get("PLAY_ACCESS_TOKEN")
    release.require(bool(token), "WIF access token unavailable.")
    base = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + release.PACKAGE + "/edits"
    edit = release.request_json("POST", base, token, {})
    edit_id = edit.get("id")
    release.require(isinstance(edit_id, str) and bool(edit_id), "Play edit identifier unavailable.")
    root = base + "/" + urllib.parse.quote(edit_id, safe="")
    try:
        tracks = release.request_json("GET", root + "/tracks", token)
        bundles = release.request_json("GET", root + "/bundles", token)
        apks = release.request_json("GET", root + "/apks", token)
        release.require(isinstance(tracks.get("tracks"), list), "Track inventory unavailable.")
        bundle_list = bundles.get("bundles", [])
        apk_list = apks.get("apks", [])
        release.require(isinstance(bundle_list, list) and isinstance(apk_list, list), "Binary inventory unavailable.")
        all_codes = version_codes(bundle_list) + version_codes(apk_list)
        internal = []
        for track in tracks["tracks"]:
            for item in track.get("releases", []):
                codes = version_codes([{"versionCode": v} for v in item.get("versionCodes", [])])
                all_codes.extend(codes)
                if track.get("track") == "internal":
                    internal.append({"version_codes": codes, "status": item.get("status")})
        release.require(any(t.get("track") == "internal" for t in tracks["tracks"]), "Existing internal track not found.")
        version, name = release.source_version()
        report = {
            "package": release.PACKAGE,
            "source_sha": sha,
            "candidate_version_code": version,
            "candidate_version_name": name,
            "highest_visible_version_code": max(all_codes, default=0),
            "candidate_above_visible_inventory": version > max(all_codes, default=0),
            "internal_releases": internal,
            "wif_api_read_ok": True,
            "published": False,
            "write_permission_verified": False,
        }
    finally:
        release.request_json("DELETE", root, token)
    guard()
    release.write_json("play-preflight/report.json", report)
    print("WIF and Play inventory read verified; no release was uploaded or committed.")
    release.require(report["candidate_above_visible_inventory"], "Candidate versionCode is not above visible Play inventory. Review before any release.")
    return report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("guard", "check"))
    if parser.parse_args().action == "guard":
        guard()
    else:
        check()


if __name__ == "__main__":
    try:
        main()
    except release.Blocked as error:
        raise SystemExit(str(error)) from None
