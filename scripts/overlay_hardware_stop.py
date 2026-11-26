"""Hardware stop transport for the disposable Android CI emulator.

Transport acknowledgement is not proof of key delivery. The caller must also
require the fresh application callback and released input state.
"""
import json
import re
import subprocess
import time


def press_and_release(serial, proto_dir, run=subprocess.run, sleep=time.sleep):
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("Disposable emulator required")

    def event(event_type):
        payload = json.dumps({"codeType": "Evdev", "eventType": event_type, "keyCode": 114})
        command = ["grpcurl", "-plaintext", "-max-time", "2", "-import-path", str(proto_dir),
                   "-proto", "emulator_controller.proto", "-d", payload, "127.0.0.1:8556",
                   "android.emulation.control.EmulatorController/sendKey"]
        try:
            result = run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                         timeout=3, check=False)
            return {"returncode": result.returncode,
                    "error": None if result.returncode == 0 else "rpc_failed",
                    "accepted": result.returncode == 0}
        except (OSError, subprocess.SubprocessError) as failure:
            return {"returncode": None, "error": type(failure).__name__, "accepted": False}

    try:
        press = event("keydown")
        sleep(0.12)
    finally:
        # Release even if the press response was lost.
        release = event("keyup")
    return {"press": press, "release": release,
            "press_ok": press["accepted"], "release_ok": release["accepted"]}


def classify_guest_volume_events(returncode, output, observer_started=True):
    """Return only fixed-key counts, never store the raw device event stream.

    An empty/partial stream is inconclusive; getevent may be unavailable to
    the unprivileged shell or may not see the emulator's injection route.
    """
    lines = output.splitlines()
    down = sum(bool(re.search(r"\bEV_KEY\s+KEY_VOLUMEDOWN\s+(?:DOWN|00000001)\s*$", line))
               for line in lines)
    up = sum(bool(re.search(r"\bEV_KEY\s+KEY_VOLUMEDOWN\s+(?:UP|00000000)\s*$", line))
             for line in lines)
    unavailable = (not observer_started or any(
        re.search(r"permission denied|could not open|cannot open|not found|inaccessible",
                  line, re.IGNORECASE) for line in lines))
    if down or up:
        outcome = "volume_event_observed"
    elif unavailable or returncode not in (0, 124, 137, 143):
        outcome = "observer_unavailable"
    else:
        outcome = "not_observed_in_available_stream"
    return {"outcome": outcome, "down_count": down, "up_count": up,
            "observer_exit_code": returncode, "observation_incomplete": unavailable}


def observe_guest_volume_events(serial, send, popen=subprocess.Popen, sleep=time.sleep):
    """Observe one existing stop request without changing device permissions."""
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("Disposable emulator console required")
    process = None
    started = False
    raw = b""
    code = None
    try:
        # Bounded, read-only trace on the CI fixture. No adb root or permission
        # changes, and no raw event output is persisted or printed.
        process = popen(["adb", "-s", serial, "shell", "timeout", "3", "getevent", "-lt"],
                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        sleep(0.2)
        started = process.poll() is None
    except (OSError, subprocess.SubprocessError):
        pass
    try:
        command = send()
    finally:
        if process is not None:
            try:
                raw, _ = process.communicate(timeout=4)
                code = process.returncode
            except subprocess.TimeoutExpired:
                process.kill()
                raw, _ = process.communicate()
                code = process.returncode
                started = False
    return command, classify_guest_volume_events(
        code, raw.decode("utf-8", errors="replace"), started)
