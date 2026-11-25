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
        release.require(isinstance(tracks.get("tracks"), list), "Track inventory unavailable.")
        release.require(any(t.get("track") == "internal" for t in tracks["tracks"]), "Existing internal track not found.")
        highest, internal = release.play_inventory(root, tracks["tracks"], token)
        version, name = release.source_version()
        report = {
            "package": release.PACKAGE,
            "source_sha": sha,
            "candidate_version_code": version,
            "candidate_version_name": name,
            "highest_visible_version_code": highest,
            "candidate_above_visible_inventory": version > highest,
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
