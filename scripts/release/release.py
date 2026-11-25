"""Manual fixed-source signing and internal/closed publishing; no production path."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import ssl
import subprocess
import tempfile
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

REPO = "hebi10/DualTouch"
PACKAGE = "com.hebi.dualtouch"
ANDROID = "{http://schemas.android.com/apk/res/android}"


class Blocked(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise Blocked(message)


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def runtime_digest(root=Path(".")):
    files = sorted(p for p in (root / "app/src/main").rglob("*") if p.is_file())
    files += [root / p for p in ("app/build.gradle.kts", "build.gradle.kts", "settings.gradle.kts", "gradle.properties") if (root / p).is_file()]
    digest = hashlib.sha256()
    for path in sorted(files):
        digest.update(path.relative_to(root).as_posix().encode() + b"\0" + path.read_bytes() + b"\0")
    return digest.hexdigest()


def readiness(config, digest):
    require(config.get("schema") == 1, "Unsupported release readiness schema.")
    require(config.get("device_qa_passed") is True, "Physical-device touch/safety QA is not passed.")
    require(config.get("reviewed_runtime_digest") == digest, "Device QA belongs to a different runtime source.")
    require(str(config.get("device_qa_evidence_url", "")).startswith("https://"), "Device QA evidence is missing.")
    require(config.get("accessibility_review_ready") is True, "Accessibility review material is incomplete.")
    require(str(config.get("accessibility_demo_url", "")).startswith("https://"), "Actual accessibility demo is missing.")
    require(re.fullmatch(r"[a-f0-9]{64}", config.get("upload_certificate_sha256", "")), "Existing upload certificate pin is not configured.")


def request_json(method, url, token, data=None, binary=False):
    require(bool(token), "Required scoped authentication is unavailable.")
    payload = data if binary else (json.dumps(data).encode() if data is not None else None)
    headers = {"Authorization": "Bearer " + token, "Content-Type": "application/octet-stream" if binary else "application/json"}
    req = urllib.request.Request(url, data=payload, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=180) as response:
            body = response.read()
        return json.loads(body) if body else {}
    except urllib.error.HTTPError as error:
        raise Blocked("Remote API rejected the request (HTTP %s); no automatic fallback or retry." % error.code) from None


def github(path):
    return request_json("GET", "https://api.github.com/repos/" + REPO + "/" + path, os.environ.get("GH_TOKEN"))


def require_current_main():
    sha = os.environ.get("EXPECTED_MAIN_SHA", "")
    require(os.environ.get("RELEASE_APPROVED") == "true", "Explicit release approval is required.")
    require(os.environ.get("GITHUB_REPOSITORY") == REPO and os.environ.get("GITHUB_REF") == "refs/heads/main", "Release workflows run only on DualTouch main.")
    require(re.fullmatch(r"[a-f0-9]{40}", sha) and sha == os.environ.get("GITHUB_SHA"), "Approved SHA differs from workflow source.")
    require(github("git/ref/heads/main")["object"]["sha"] == sha, "Main changed; freeze and review the new source.")
    return sha


def guard():
    sha = require_current_main()
    stage = os.environ.get("RELEASE_STAGE", "bundle")
    require(stage in ("bundle", "internal", "closed"), "Invalid release stage.")
    if stage != "bundle":
        run = os.environ.get("SOURCE_RUN_ID", "")
        require(re.fullmatch(r"[1-9][0-9]*", run), "Invalid source artifact run ID.")
        validate_run(github("actions/runs/" + run), "play-bundle.yml" if stage == "internal" else "play-internal-release.yml", sha)
    config = read_json("release/readiness.json")
    readiness(config, runtime_digest())
    runs = github("actions/runs?head_sha=" + sha + "&per_page=100")["workflow_runs"]
    require(any(r.get("head_sha") == sha and r.get("path") == ".github/workflows/android-ci.yml" and r.get("status") == "completed" and r.get("conclusion") == "success" for r in runs), "Exact-main Android CI has not passed.")
    return sha, config


def source_version():
    text = Path("app/build.gradle.kts").read_text(encoding="utf-8")
    code = re.search(r"versionCode\s*=\s*(\d+)", text)
    name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    require(code and name, "Source version is missing.")
    return int(code[1]), name[1]


def internal_qa_guard():
    require(os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch", "Internal QA requires a manual release request.")
    sha = require_current_main()
    version = os.environ.get("EXPECTED_VERSION_CODE", "")
    require(re.fullmatch(r"[1-9][0-9]*", version) and version == str(source_version()[0]), "Approved versionCode differs from source.")
    config = read_json("release/readiness.json")
    require(config.get("schema") == 1 and re.fullmatch(r"[a-f0-9]{64}", config.get("upload_certificate_sha256", "")), "Existing upload certificate pin is not configured.")
    runs = github("actions/workflows/android-ci.yml/runs?branch=main&event=push&head_sha=" + sha)["workflow_runs"]
    require(bool(runs) and runs[0].get("head_sha") == sha and runs[0].get("status") == "completed" and runs[0].get("conclusion") == "success", "Latest exact-main Android CI has not passed.")
    return sha, config


def restore():
    config = read_json("release/readiness.json")
    pin = config.get("upload_certificate_sha256", "")
    encoded = os.environ.get("DUALTOUCH_SIGNING_BUNDLE_BASE64", "")
    require(encoded and re.fullmatch(r"[a-f0-9]{64}", pin), "Existing DualTouch signing bundle/certificate pin is unavailable.")
    require(len(encoded) <= 2_097_152, "Signing archive too large.")
    try:
        with zipfile.ZipFile(io.BytesIO(base64.b64decode("".join(encoded.split()), validate=True))) as z:
            members = z.infolist()
            names = {m.filename for m in members}
            keys = names & {"dualtouch-signing.p12", "dualtouch-signing.jks"}
            require(len(keys) == 1 and len(members) == 4 and names == keys | {"signing.properties", "certificate.sha256", "RESTORE.txt"}, "Unexpected signing archive contents.")
            require(sum(m.file_size for m in members) <= 1_048_576, "Signing archive expanded size too large.")
            data = {m.filename: z.read(m) for m in members}
        require(data["certificate.sha256"].decode().strip().lower() == pin, "Signing archive pin mismatch.")
        props = {}
        for line in data["signing.properties"].decode("utf-8").splitlines():
            if line.strip() and not line.lstrip().startswith(("#", "!")):
                key, value = line.split("=", 1)
                require(key.strip() not in props, "Duplicate signing field.")
                props[key.strip()] = value.strip()
        key = next(iter(keys))
        require(props.get("storeFile") == key and props.get("storeType") == ("PKCS12" if key.endswith("p12") else "JKS") and all(props.get(k) for k in ("storePassword", "keyAlias", "keyPassword")), "Signing configuration incomplete.")
    except Blocked:
        raise
    except Exception:
        raise Blocked("Existing signing bundle could not be restored.") from None
    directory = Path(tempfile.mkdtemp(prefix="dualtouch-signing-", dir=os.environ["RUNNER_TEMP"]))
    directory.chmod(0o700)
    for name, content in data.items():
        with os.fdopen(os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "wb") as stream:
            stream.write(content)
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as stream:
        stream.write("DUALTOUCH_SIGNING_PROPERTIES=" + str(directory / "signing.properties") + "\n")
    print("Existing upload key restored privately; no new key generated.")


def cleanup():
    prop = os.environ.get("DUALTOUCH_SIGNING_PROPERTIES")
    if not prop:
        return
    directory = Path(prop).parent
    require(not directory.is_symlink() and directory.resolve().parent == Path(os.environ["RUNNER_TEMP"]).resolve() and directory.name.startswith("dualtouch-signing-"), "Invalid cleanup scope.")
    shutil.rmtree(directory, ignore_errors=True)


def command(args):
    result = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
    require(result.returncode == 0, "Release identity/signature verification command failed.")
    return result.stdout.decode("utf-8", errors="replace")


def verify_aab(path, sha, config):
    path = Path(path)
    require(path.is_file() and not path.is_symlink(), "AAB bytes are missing.")
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        require("base/manifest/AndroidManifest.xml" in names and len([n for n in names if re.fullmatch(r"META-INF/[^/]+\.(RSA|EC|DSA)", n)]) == 1, "AAB signer/module is missing or ambiguous.")
    result = command(["jarsigner", "-J-Duser.language=en", "-verify", str(path)])
    require("jar verified." in result.lower() and "unsigned entries" not in result.lower(), "AAB signature is invalid or contains unsigned entries.")
    certs = re.findall(r"-----BEGIN CERTIFICATE-----.*?-----END CERTIFICATE-----", command(["keytool", "-printcert", "-rfc", "-jarfile", str(path)]), re.S)
    require(bool(certs), "AAB signer certificate is unavailable.")
    cert = hashlib.sha256(ssl.PEM_cert_to_DER_cert(certs[0])).hexdigest()
    require(cert == config["upload_certificate_sha256"], "AAB signer differs from the pinned existing upload key.")
    xml = command(["java", "-jar", os.environ["BUNDLETOOL_JAR"], "dump", "manifest", "--bundle=" + str(path), "--module=base"])
    manifest = ET.fromstring(xml)
    version, name = source_version()
    sdk, app = manifest.find("uses-sdk"), manifest.find("application")
    require(manifest.get("package") == PACKAGE and manifest.get(ANDROID + "versionCode") == str(version) and manifest.get(ANDROID + "versionName") == name, "AAB package/version differs from source.")
    require(sdk is not None and sdk.get(ANDROID + "targetSdkVersion") == "36" and sdk.get(ANDROID + "minSdkVersion") == "34", "AAB SDK differs from policy-reviewed source.")
    require(app is not None and app.get(ANDROID + "debuggable", "false") == "false", "Debuggable AAB is not allowed.")
    require(not any(p.get(ANDROID + "name") in {"android.permission.INTERNET", "android.permission.QUERY_ALL_PACKAGES", "android.permission.SYSTEM_ALERT_WINDOW"} for p in manifest.findall("uses-permission")), "Unexpected data/overlay permission in release AAB.")
    return {"package": PACKAGE, "source_sha": sha, "runtime_digest": runtime_digest(), "version_code": version, "version_name": name, "aab_sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "upload_certificate_sha256": cert, "target_sdk": 36}


def stage():
    sha, config = guard()
    bundle = Path("app/build/outputs/bundle/release/app-release.aab")
    identity = verify_aab(bundle, sha, config)
    Path("play-dist").mkdir(exist_ok=True)
    shutil.copyfile(bundle, "play-dist/DualTouch.aab")
    write_json("play-dist/manifest.json", identity)


def validate_run(run, workflow, sha):
    require(run.get("head_sha") == sha and run.get("head_branch") == "main" and run.get("event") == "workflow_dispatch" and run.get("path") == ".github/workflows/" + workflow and run.get("conclusion") == "success" and run.get("status") == "completed", "Source artifact run is not the approved successful manual workflow.")


def verify_input():
    sha, config = guard()
    run = os.environ.get("SOURCE_RUN_ID", "")
    require(re.fullmatch(r"[1-9][0-9]*", run), "Invalid source build run ID.")
    validate_run(github("actions/runs/" + run), "play-bundle.yml", sha)
    identity = verify_aab("candidate/DualTouch.aab", sha, config)
    require(read_json("candidate/manifest.json") == identity, "Build manifest differs from independently verified AAB.")
    require(identity["aab_sha256"] == os.environ.get("AAB_SHA256"), "AAB bytes differ from approved digest.")
    return sha, config, identity


def internal_qa_manifest(identity, config):
    return {
        **identity,
        "distribution": "internal QA only",
        "device_qa_passed": config["device_qa_passed"],
        "accessibility_review_ready": config["accessibility_review_ready"],
    }


def verify_internal_qa():
    sha, config = internal_qa_guard()
    identity = verify_aab("candidate/DualTouch.aab", sha, config)
    require(read_json("candidate/manifest.json") == internal_qa_manifest(identity, config), "Internal QA build manifest differs from independently verified AAB.")
    return internal_qa_manifest(identity, config)


def version_codes(values):
    codes = []
    for value in values:
        value = str(value)
        require(re.fullmatch(r"[1-9][0-9]*", value), "Malformed Play version inventory.")
        codes.append(int(value))
    return codes


def play_inventory(root, tracks, token):
    bundles = request_json("GET", root + "/bundles", token).get("bundles", [])
    apks = request_json("GET", root + "/apks", token).get("apks", [])
    codes = version_codes(item.get("versionCode") for item in bundles + apks)
    internal = []
    for track in tracks:
        for item in track.get("releases", []):
            release_codes = version_codes(item.get("versionCodes", []))
            codes.extend(release_codes)
            if track["track"] == "internal":
                internal.append({"version_codes": release_codes, "status": item.get("status")})
    return max(codes, default=0), internal


def closed_payload(internal, bundles, receipt, closed, expected_hash):
    require(closed not in ("", "internal", "production", "open") and ":" not in closed, "Closed track must be the exact configured existing phone closed-test ID.")
    code = str(receipt["version_code"])
    require(receipt.get("track") == "internal" and receipt.get("committed") is True and receipt.get("readback_completed") is True and receipt.get("aab_sha256") == expected_hash, "Successful internal receipt does not match approved AAB.")
    releases = [r for r in internal.get("releases", []) if r.get("status") == "completed" and code in r.get("versionCodes", [])]
    require(len(releases) == 1, "Approved version is not completed in internal testing.")
    require(any(str(b.get("versionCode")) == code and b.get("sha256") == expected_hash for b in bundles.get("bundles", [])), "Play bundle digest differs from approved internal bytes.")
    release = {"name": releases[0].get("name", "DualTouch"), "versionCodes": [code], "status": "completed", "releaseNotes": releases[0].get("releaseNotes", [])}
    return {"track": closed, "releases": [release]}


def play(action):
    require(action in ("internal", "internal-qa", "closed"), "Unsupported publishing action.")
    check_source = guard
    if action == "internal-qa":
        check_source = internal_qa_guard
        identity = verify_internal_qa()
        track = "internal"
    elif action == "internal":
        sha, config, identity = verify_input()
        track = "internal"
    else:
        sha, config = guard()
        run = os.environ.get("SOURCE_RUN_ID", "")
        require(re.fullmatch(r"[1-9][0-9]*", run), "Invalid internal release run ID.")
        validate_run(github("actions/runs/" + run), "play-internal-release.yml", sha)
        identity = read_json("candidate/receipt.json")
        require(identity.get("source_sha") == sha and identity.get("package") == PACKAGE, "Internal receipt belongs to another source or app.")
        track = config.get("closed_track_id", "")
        require(track and track not in ("internal", "production", "open") and ":" not in track, "Verified closed track ID is not configured.")
    token = os.environ.get("PLAY_ACCESS_TOKEN")
    base = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" + PACKAGE + "/edits"
    edit_id = request_json("POST", base, token, {})["id"]
    root = base + "/" + urllib.parse.quote(edit_id, safe="")
    committed = False
    try:
        tracks = request_json("GET", root + "/tracks", token)
        require(any(t["track"] == track for t in tracks.get("tracks", [])), "Target track does not exist. Configure it in Play Console first.")
        if action != "closed":
            if action == "internal-qa":
                highest, internal = play_inventory(root, tracks["tracks"], token)
                require(identity["version_code"] > highest, "Approved versionCode already exists or is older than Play inventory.")
                require(all(r["status"] == "completed" for r in internal), "Resolve the pending internal release in Play Console before publishing.")
            upload = "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/" + PACKAGE + "/edits/" + urllib.parse.quote(edit_id, safe="") + "/bundles?uploadType=media"
            result = request_json("POST", upload, token, Path("candidate/DualTouch.aab").read_bytes(), binary=True)
            require(result.get("sha256") == identity["aab_sha256"] and result.get("versionCode") == identity["version_code"], "Play upload identity differs from verified AAB.")
            notes = read_json("store/ko-KR-listing.json")["release_notes"]
            payload = {"track": track, "releases": [{"name": identity["version_name"], "versionCodes": [str(identity["version_code"])], "status": "completed", "releaseNotes": [{"language": "ko-KR", "text": notes}]}]}
        else:
            internal = request_json("GET", root + "/tracks/internal", token)
            bundles = request_json("GET", root + "/bundles", token)
            payload = closed_payload(internal, bundles, identity, track, os.environ.get("AAB_SHA256", ""))
        check_source()
        target = "/tracks/" + urllib.parse.quote(track, safe="")
        request_json("PUT", root + target, token, payload)
        request_json("POST", root + ":validate", token, {})
        check_source()
        request_json("POST", root + ":commit", token, {})
        committed = True
        receipt = {**identity, "track": track, "committed": True, "readback_completed": False}
        write_json("play-receipt/receipt.json", receipt)
        check_id = request_json("POST", base, token, {})["id"]
        check_root = base + "/" + urllib.parse.quote(check_id, safe="")
        try:
            readback = request_json("GET", check_root + target, token)
            receipt["readback_completed"] = any(r.get("status") == "completed" and str(identity["version_code"]) in r.get("versionCodes", []) for r in readback.get("releases", []))
            write_json("play-receipt/receipt.json", receipt)
            require(receipt["readback_completed"], "Play committed but completed-track readback is not verified. Inspect Console; do not retry automatically.")
        finally:
            request_json("DELETE", check_root, token)
        print("Play API commit/readback verified. Console review and tester availability require separate verification.")
    finally:
        if not committed:
            request_json("DELETE", root, token)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["guard", "restore", "cleanup", "stage", "verify", "internal", "internal-qa-guard", "verify-internal-qa", "internal-qa", "closed", "digest"])
    action = parser.parse_args().action
    if action == "digest":
        print(runtime_digest())
    elif action == "verify":
        verify_input()
    elif action == "verify-internal-qa":
        verify_internal_qa()
    elif action in ("internal", "internal-qa", "closed"):
        play(action)
    else:
        {"guard": guard, "internal-qa-guard": internal_qa_guard, "restore": restore, "cleanup": cleanup, "stage": stage}[action]()


if __name__ == "__main__":
    try:
        main()
    except Blocked as error:
        raise SystemExit(str(error)) from None
