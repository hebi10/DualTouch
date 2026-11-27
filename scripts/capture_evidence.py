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


def controller_focused(dump):
    return bool(re.search(
        r"(?m)^\s*mCurrentFocus=Window\{[^\n]*\scom\.hebi\.dualtouch/(?:com\.hebi\.dualtouch)?\.(?:MainActivity|SettingsActivity)\}\s*$",
        dump,
    ))
