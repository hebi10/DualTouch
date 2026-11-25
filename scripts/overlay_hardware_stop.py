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
