"""Hardware stop transport for the disposable Android CI emulator.

Transport acknowledgement is not proof of key delivery. The caller must also
require the fresh application callback and released input state.
"""
import re
import subprocess
import time


def console_result(returncode, output):
    lines = [line.strip() for line in output.splitlines() if line.strip()]
    rejected = any(line == "KO" or line.startswith("KO:") for line in lines)
    # Keep only a classification, never dump arbitrary console/auth output.
    if rejected:
        error = "console_rejected"
    elif returncode != 0:
        error = "nonzero_exit"
    elif any(line != "OK" for line in lines):
        error = "unexpected_response"
    else:
        error = None
    return {
        "returncode": returncode,
        "acknowledgement": "ok" if "OK" in lines else "silent",
        "error": error,
        "accepted": error is None,
    }


def press_and_release(serial, run=subprocess.run, sleep=time.sleep):
    if not re.fullmatch(r"emulator-\d+", serial):
        raise ValueError("Disposable emulator console required")

    def event(value):
        # Linux EV_KEY=1, KEY_VOLUMEDOWN=114, EV_SYN/SYN_REPORT=0.
        # Numeric fields avoid emulator-version-specific code aliases.
        command = ["adb", "-s", serial, "emu", "event", "send",
                   f"1:114:{value}", "0:0:0"]
        try:
            result = run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         timeout=3, check=False)
            output = result.stdout.decode("utf-8", errors="replace")
            return console_result(result.returncode, output)
        except (OSError, subprocess.SubprocessError) as failure:
            return {"returncode": None, "acknowledgement": "unavailable",
                    "error": type(failure).__name__, "accepted": False}

    evidence = {"press": None, "release": None}
    try:
        evidence["press"] = event(1)
        sleep(0.12)
    finally:
        # Always release, even if transport acknowledgement was lost.
        evidence["release"] = event(0)
    evidence["press_ok"] = evidence["press"]["accepted"]
    evidence["release_ok"] = evidence["release"]["accepted"]
    return evidence


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
