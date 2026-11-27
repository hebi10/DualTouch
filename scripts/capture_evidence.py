"""Verify immutable, already captured emulator PNG bytes; do not take a new screenshot."""
import hashlib
import re
import struct
import subprocess
import time

PNG = b"\x89PNG\r\n\x1a\n"


def read_verified_png(reader, name, expected_hash, width, height, pause=time.sleep):
    """Bounded retry of file transport only. Reject wrong bytes on every attempt."""
    last_error = None
    for attempt in range(3):
        try:
            data = reader(name)
            if len(data) < 33 or not data.startswith(PNG) or data[12:16] != b"IHDR":
                raise ValueError("Invalid PNG header")
            if struct.unpack(">II", data[16:24]) != (width, height):
                raise ValueError("PNG size differs from capture report")
            if hashlib.sha256(data).hexdigest() != expected_hash:
                raise ValueError("PNG SHA-256 differs from capture report")
            return data
        except (ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
            last_error = error
            if attempt < 2:
                pause(0.3 * (attempt + 1))
    raise AssertionError(f"Cannot recover verified PNG {name}: {last_error}") from last_error


def controller_resumed(dump):
    return bool(re.search(
        r"(?m)^\s*(?:topResumedActivity|mResumedActivity)\s*[:=]\s*ActivityRecord\{[^\n]*\scom\.hebi\.dualtouch/(?:com\.hebi\.dualtouch)?\.(?:MainActivity|SettingsActivity)(?:\s|\})",
        dump,
    ))


def messages_anr_focused(dump):
    return bool(re.search(
        r"(?m)^\s*mCurrentFocus=Window\{[^\n]*Application Not Responding: com\.google\.android\.apps\.messaging\}\s*$",
        dump,
    ))


def focused_windows(dump):
    # Some Android versions omit mCurrentFocus from the window-only dump.
    # An explicit foreign/null focus is not the same as missing metadata.
    return re.findall(r"(?m)^\s*mCurrentFocus\s*=\s*([^\r\n]+)", dump)


def controller_focused(dump):
    windows = focused_windows(dump)
    return bool(windows) and all(re.fullmatch(
        r"Window\{[^\n]*\scom\.hebi\.dualtouch/(?:com\.hebi\.dualtouch)?\.(?:MainActivity|SettingsActivity)\}",
        window.strip(),
    ) for window in windows)


def controller_focus_verified(window_dump, activity_dump):
    # The activity diagnostic used by Android 15 also exposes the current
    # window and resumed Activity. Always require the latter, and reject
    # explicit disagreement; fall back only if the window query omits focus.
    if not controller_resumed(activity_dump):
        return False
    if focused_windows(window_dump):
        if not controller_focused(window_dump):
            return False
        return not focused_windows(activity_dump) or controller_focused(activity_dump)
    return controller_focused(activity_dump)
