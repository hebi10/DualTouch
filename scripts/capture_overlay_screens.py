"""Drive the optional probe instrumentation on a disposable CI emulator only."""
import hashlib
import json
import os
import re
import subprocess
import time
from pathlib import Path

from overlay_hardware_stop import press_and_release, observe_guest_volume_events

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
assert SCENARIO in {"countdown", "calibration", "home-return"}
EXPECTED = COMMON | ({SCENARIO_CAPTURE[SCENARIO]} if SCENARIO in SCENARIO_CAPTURE else set())
HOME_EVIDENCE = {"QA-home-after-window-change.png", "QA-home-after-hide.png"}
ALLOWED = EXPECTED | {"QA-stopped.png", "diagnostic-failure.png",
                      "diagnostic-countdown.png", "diagnostic-calibration.png",
                      "diagnostic-stop-failure.png"}
if SCENARIO == "home-return":
    ALLOWED |= HOME_EVIDENCE


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


def stop_evidence():
    # Only constant diagnostic markers emitted by the real service callback.
    raw = adb("logcat", "-d", "-v", "raw", "-s", "DualTouchStopQA:I").decode("utf-8", errors="replace")
    return [line.strip() for line in raw.splitlines() if re.fullmatch(
        r"volume_down_received|volume_down_completed input_off=(?:true|false)", line.strip())]


def send_hardware_volume_down():
    evidence = {"scenario": SCENARIO, "source_commit": SOURCE,
                "method": "emulator_console_numeric_EV_KEY",
                "press_ok": False, "release_ok": False}
    try:
        serial = adb("get-serialno", timeout=3).decode().strip()
        command, guest = observe_guest_volume_events(serial, lambda: press_and_release(serial))
        evidence.update(command)
        evidence["guest_volume_observation"] = guest
    except Exception as error:
        evidence["transport_error"] = type(error).__name__
    finally:
        (OUT / "hardware-stop-command.json").write_text(
            json.dumps(evidence, indent=2), encoding="utf-8")
    return evidence


def execute_scenario():
    stream = OUT / "instrumentation.txt"
    marker = f"INSTRUMENTATION_STATUS: dualtouch_stop_ready={SOURCE}:{SCENARIO}".encode()
    baseline = stop_evidence()
    requested = False
    command = None
    deadline = time.monotonic() + 480
    with stream.open("wb") as stdout:
        process = subprocess.Popen(
            ["adb", "shell", "am", "instrument", "-w", "-r",
             "-e", "source_sha", SOURCE, "-e", "scenario", SCENARIO, RUNNER],
            stdout=stdout, stderr=subprocess.STDOUT,
        )
        try:
            while process.poll() is None:
                if not requested and marker in stream.read_bytes().splitlines():
                    requested = True
                    assert SCENARIO in SCENARIO_CAPTURE, "Unexpected hardware stop in idle home scenario"
                    # A transport failure is still a failed stop test, but must
                    # not kill instrumentation before it saves its evidence.
                    command = send_hardware_volume_down()
                if time.monotonic() >= deadline:
                    raise TimeoutError("Instrumentation scenario exceeded host deadline")
                time.sleep(0.2)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)
    return process.returncode, stream.read_bytes(), requested, baseline, command


def collect():
    final_report = True
    try:
        report = json.loads(read_private("report.json").decode("utf-8"))
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired, ValueError):
        # Only already verified screenshots from an atomic checkpoint may be
        # recovered. A missing final report can never pass a scenario.
        final_report = False
        report = json.loads(read_private("checkpoint.json").decode("utf-8"))
    assert report["source_commit"] == SOURCE, "Capture source SHA differs"
    assert report["scenario"] == SCENARIO, "Capture scenario differs"
    assert report["physical_foldable_verified"] is False
    assert report["touch_mirroring_verified"] is False
    report["workflow_commit"] = os.environ.get("GITHUB_SHA", "unknown")
    report["checkout_commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    report["final_report_available"] = final_report
    (OUT / "report.json").write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
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
    assert final_report, "Only partial capture checkpoint recovered"
    assert report["completed"], report.get("error")
    assert verified == EXPECTED, f"Missing actual design states: {EXPECTED - verified}"
    if SCENARIO == "home-return":
        captured = {item["file"] for item in report["captures"]}
        assert HOME_EVIDENCE <= captured, "Missing actual home transition evidence"
        assert any(item.get("home_return_safe_state_verified") is True and
                   item.get("original_connected_home_design_verified") is False
                   for item in report["observations"]), "Safe home transition not verified"
        assert any(item.get("home_hide_verified") is True for item in report["observations"]), "Floating hide not verified"
        assert not (verified & HOME_EVIDENCE), "Alternate home state must not count as a connected-design match"


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
    code, stdout, requested, baseline, command = execute_scenario()
    current = stop_evidence()
    # A fresh disposable emulator provides a unique trial. Reject stale,
    # missing or duplicate evidence; no successful inference from screenshots.
    assert current[:len(baseline)] == baseline, "Stop diagnostic sequence changed"
    callbacks = current[len(baseline):]
    (OUT / "hardware-stop-callbacks.json").write_text(json.dumps({
        "scenario": SCENARIO, "source_commit": SOURCE, "requested": requested,
        "callbacks": callbacks
    }, indent=2), encoding="utf-8")
    # Copy all verified screenshots before evaluating the stop result.
    collect()
    assert code == 0 and b"INSTRUMENTATION_CODE: -1" in stdout, "Instrumentation did not finish successfully"
    if SCENARIO == "home-return":
        assert not requested and not callbacks, "Idle home scenario must not request a hardware stop"
    else:
        assert requested, "No hardware stop request was observed"
        assert command and command["press_ok"] and command["release_ok"], "Emulator hardware-button command failed"
        assert callbacks == ["volume_down_received", "volume_down_completed input_off=true"], "Real key callback and input release not verified"
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
