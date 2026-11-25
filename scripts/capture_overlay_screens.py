"""Drive the optional probe instrumentation on a disposable CI emulator only."""
import hashlib
import json
import os
import re
import subprocess
import time
from pathlib import Path

ROOT = Path("qa/overlay-apk")
OUT = Path("qa/overlay-screenshots")
OUT.mkdir(parents=True, exist_ok=True)
PKG = "com.hebi.dualtouch.probeleft"
RUNNER = PKG + ".test/" + PKG + ".OverlayCaptureRunner"
SOURCE = os.environ["SOURCE_SHA"]
SCENARIO = os.environ["OVERLAY_SCENARIO"]
SDK_SETUP = "com.google.android.googlesdksetup"
COMMON = {"DES-06-thumb-ready.png", "DES-07-thumb-menu.png", "DES-08-pair-selection.png"}
SCENARIO_CAPTURE = {"countdown": "DES-09-thumb-running.png", "calibration": "DES-10-calibration.png"}
EXPECTED = COMMON | {SCENARIO_CAPTURE[SCENARIO]}
ALLOWED = EXPECTED | {"QA-stopped.png", "diagnostic-failure.png",
                      "diagnostic-countdown.png", "diagnostic-calibration.png",
                      "diagnostic-stop-failure.png"}


def adb(*args, timeout=45):
    return subprocess.check_output(["adb", *args], timeout=timeout)


def shell_text(*args):
    return adb("shell", *args).decode("utf-8", errors="replace").strip()


def prepare_fixture():
    """Keep the known Google SDK setup ANR out of a fully provisioned CI fixture.

    This never disables a package, writes a setting, or changes accessibility
    permissions. Never run the preparation on a physical or unprovisioned device.
    """
    observations = {"source_commit": SOURCE, "setup_package": SDK_SETUP}
    report_path = OUT / "fixture-readiness.json"
    try:
        assert os.environ.get("GITHUB_ACTIONS") == "true", "CI fixture only"
        assert shell_text("getprop", "ro.kernel.qemu") == "1", "Emulator required"
        deadline = time.monotonic() + 60
        while True:
            booted = shell_text("getprop", "sys.boot_completed") == "1"
            provisioned = shell_text("settings", "get", "global", "device_provisioned") == "1"
            setup_complete = shell_text("settings", "get", "secure", "user_setup_complete") == "1"
            observations.update(boot_completed=booted, device_provisioned=provisioned,
                                user_setup_complete=setup_complete)
            if booted and provisioned and setup_complete:
                break
            if time.monotonic() >= deadline:
                raise AssertionError("CI emulator setup did not finish; no setup process was stopped")
            time.sleep(3)
        present = shell_text("pm", "path", SDK_SETUP).startswith("package:")
        observations["sdk_setup_present"] = present
        if present:
            # The exact process shown in R3's ANR. It is not DualTouch, Settings,
            # SystemUI, or a permission controller. Only after completed setup.
            adb("shell", "am", "force-stop", SDK_SETUP)
            observations["sdk_setup_force_stopped"] = True
        time.sleep(5)
        assert shell_text("getprop", "sys.boot_completed") == "1"
        assert shell_text("settings", "get", "global", "device_provisioned") == "1"
        assert shell_text("settings", "get", "secure", "user_setup_complete") == "1"
        observations["ready"] = True
    except Exception as error:
        observations.update(ready=False, error=str(error))
        raise
    finally:
        report_path.write_text(json.dumps(observations, ensure_ascii=False, indent=2),
                               encoding="utf-8")


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
    assert report["scenario"] == SCENARIO, "Capture scenario differs"
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
    assert os.environ.get("GITHUB_ACTIONS") == "true", "Disposable CI only"
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
    prepare_fixture()
    result = subprocess.run(
        ["adb", "shell", "am", "instrument", "-w", "-r",
         "-e", "source_sha", SOURCE, "-e", "scenario", SCENARIO, RUNNER],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=480, check=False,
    )
    (OUT / "instrumentation.txt").write_bytes(result.stdout)
    collect()
    assert result.returncode == 0 and b"INSTRUMENTATION_CODE: -1" in result.stdout, "Instrumentation did not finish successfully"
except Exception as error:
    failure = str(error)
    (OUT / "host-failure.json").write_text(json.dumps({
        "source_commit": SOURCE, "scenario": SCENARIO, "error": failure, "physical_foldable_verified": False,
        "touch_mirroring_verified": False,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    # Keep a failure report without reclassifying a blocked state as a design capture.
    if is_emulator and not (OUT / "report.json").exists():
        try:
            collect()
        except Exception:
            pass
finally:
    if is_emulator:
        # Retain only the controller's exact numeric diagnostic format; never
        # export general logcat or screen/input data.
        try:
            lines = adb("logcat", "-d", "-v", "raw", "-s", "DualTouchInspection:I").decode("utf-8", errors="replace").splitlines()
            pattern = re.compile(r"inspection total_ms=\d+ windows_ms=\d+ app_root_ms=\d+ overlay_root_ms=\d+ tree_ms=\d+ work_area_ms=\d+ other_ms=\d+ outcome=(complete|timeout|exception)")
            matches = [line for line in lines if pattern.fullmatch(line)]
            (OUT / "inspection-timing.txt").write_text("\n".join(matches[-200:]) + "\n", encoding="utf-8")
        except Exception:
            (OUT / "inspection-timing.txt").write_text("Numeric timing collection unavailable\n", encoding="utf-8")
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
