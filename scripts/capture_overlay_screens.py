"""Drive the optional probe instrumentation on a disposable CI emulator only."""
import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path("qa/overlay-apk")
OUT = Path("qa/overlay-screenshots")
OUT.mkdir(parents=True, exist_ok=True)
PKG = "com.hebi.dualtouch.probeleft"
RUNNER = PKG + ".test/" + PKG + ".OverlayCaptureRunner"
SOURCE = os.environ["SOURCE_SHA"]
EXPECTED = {
    "DES-06-thumb-ready.png", "DES-07-thumb-menu.png",
    "DES-08-pair-selection.png", "DES-09-thumb-running.png",
    "DES-10-calibration.png",
}
ALLOWED = EXPECTED | {"QA-stopped.png", "diagnostic-failure.png"}


def adb(*args, timeout=45):
    return subprocess.check_output(["adb", *args], timeout=timeout)


def one(directory, pattern):
    files = list(directory.rglob(pattern))
    if len(files) != 1:
        raise AssertionError(f"Expected exactly one {pattern} in {directory}; got {len(files)}")
    return files[0]


def read_private(name):
    return adb("exec-out", "run-as", PKG, "cat", "files/overlay-capture/" + name)


def collect():
    report = json.loads(read_private("report.json").decode("utf-8"))
    report["workflow_commit"] = os.environ.get("GITHUB_SHA", "unknown")
    report["checkout_commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    (OUT / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    assert report["source_commit"] == SOURCE, "Capture source SHA differs"
    assert report["physical_foldable_verified"] is False
    assert report["touch_mirroring_verified"] is False
    verified = set()
    for capture in report["captures"]:
        name = capture["file"]
        assert name in ALLOWED, "Unexpected screenshot filename"
        data = read_private(name)
        assert data.startswith(b"\x89PNG\r\n\x1a\n"), "Not a PNG"
        assert hashlib.sha256(data).hexdigest() == capture["sha256"], "Capture integrity failure"
        (OUT / name).write_bytes(data)
        if capture["design_state_verified"]:
            verified.add(name)
    assert report["completed"], report.get("error")
    assert verified == EXPECTED, f"Missing actual design states: {EXPECTED - verified}"


failure = None
is_emulator = False
try:
    assert re.fullmatch(r"[a-f0-9]{40}", SOURCE)
    is_emulator = adb("shell", "getprop", "ro.kernel.qemu").strip() == b"1"
    assert is_emulator, "Disposable emulator required"
    # Existing APKs from the same CI build; never use a personal device.
    for path in [
        one(ROOT / "main", "app-debug.apk"),
        one(ROOT / "probes", "probe-left-debug.apk"),
        one(ROOT / "probes", "probe-right-debug.apk"),
        one(ROOT / "test", "*.apk"),
    ]:
        adb("install", "-r", str(path), timeout=90)
    adb("shell", "wm", "size", "1680x1440")
    adb("shell", "wm", "density", "320")
    adb("shell", "cmd", "uimode", "night", "no")
    adb("shell", "wm", "dismiss-keyguard")
    result = subprocess.run(
        ["adb", "shell", "am", "instrument", "-w", "-r", "-e", "source_sha", SOURCE, RUNNER],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=360, check=False,
    )
    (OUT / "instrumentation.txt").write_bytes(result.stdout)
    collect()
    assert result.returncode == 0 and b"INSTRUMENTATION_CODE: -1" in result.stdout, "Instrumentation did not finish successfully"
except Exception as error:
    failure = str(error)
    (OUT / "host-failure.json").write_text(json.dumps({
        "source_commit": SOURCE, "error": failure, "physical_foldable_verified": False,
        "touch_mirroring_verified": False,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    # Keep a failure report without reclassifying a blocked state as a design capture.
    if is_emulator and not (OUT / "report.json").exists():
        try:
            collect()
        except Exception:
            pass
finally:
    for command in [
        ("input", "keyevent", "KEYCODE_VOLUME_DOWN"),
        ("am", "force-stop", "com.hebi.dualtouch"),
        ("wm", "size", "reset"),
        ("wm", "density", "reset"),
    ]:
        if is_emulator:
            try:
                adb("shell", *command)
            except Exception:
                pass
if failure:
    raise SystemExit(f"Overlay screenshot capture incomplete: {failure}")
