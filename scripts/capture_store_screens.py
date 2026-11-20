"""Capture real emulator UI; this does not validate touch mirroring."""
import hashlib
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE = "com.hebi.dualtouch"
OUTPUT = Path("qa/store-screenshots")
OUTPUT.mkdir(parents=True, exist_ok=True)
records = []


def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=45)


def shell(*args):
    return adb("shell", *args).decode("utf-8", errors="replace")


def nodes():
    for _ in range(6):
        shell("rm", "-f", "/sdcard/dualtouch-window.xml")
        shell("uiautomator", "dump", "--compressed", "/sdcard/dualtouch-window.xml")
        raw = adb("exec-out", "cat", "/sdcard/dualtouch-window.xml")
        try:
            tree = ET.fromstring(raw)
            if any(n.get("package") == PACKAGE for n in tree.iter("node")):
                return tree
        except ET.ParseError:
            pass
        time.sleep(2)
    (OUTPUT / "diagnostic-screen.png").write_bytes(adb("exec-out", "screencap", "-p"))
    (OUTPUT / "diagnostic-activity.txt").write_text(shell("dumpsys", "activity", "activities"))
    (OUTPUT / "diagnostic-crash.txt").write_text(shell("logcat", "-d", "-b", "crash"))
    raise AssertionError("App accessibility root was unavailable after bounded retries")


def tap(text):
    for _ in range(8):
        tree = nodes()
        for node in tree.iter("node"):
            if node.get("text") == text:
                bounds = [int(v) for v in re.findall(r"\d+", node.get("bounds", ""))]
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    shell("input", "tap", str((bounds[0] + bounds[2]) // 2),
                          str((bounds[1] + bounds[3]) // 2))
                    time.sleep(1)
                    return
        size = re.findall(r"(\d+)x(\d+)", shell("wm", "size"))[-1]
        width, height = map(int, size)
        shell("input", "swipe", str(width // 2), str(height * 3 // 4),
              str(width // 2), str(height // 4), "300")
    raise AssertionError("Missing visible control: " + text)


def launch():
    shell("am", "force-stop", PACKAGE)
    started = shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    print(started, flush=True)
    assert "Error:" not in started, "Activity launch failed"
    time.sleep(2)


def capture(name):
    tree = nodes()
    assert any(n.get("package") == PACKAGE for n in tree.iter("node")), "App UI missing"
    path = OUTPUT / (name + ".png")
    path.write_bytes(adb("exec-out", "screencap", "-p"))
    assert path.read_bytes().startswith(b"\x89PNG\r\n\x1a\n"), "Invalid screenshot"
    records.append({"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "size": shell("wm", "size").strip(),
                    "density": shell("wm", "density").strip()})
    print("Captured:", name, flush=True)


try:
    shell("cmd", "uimode", "night", "no")
    launch()
    capture("phone-01-intro-light")
    tap("내용을 이해하고 계속")
    capture("phone-02-home-light")
    tap("표시 설정")
    capture("phone-03-settings-light")
    launch()
    tap("플로팅 표시")
    capture("phone-04-disclosure-light")
    tap("권한 없이 돌아가기")
    launch()
    tap("분할 화면 안내")
    capture("phone-05-guide-light")
    shell("cmd", "uimode", "night", "yes")
    launch()
    capture("phone-06-home-dark")
    tap("표시 설정")
    capture("phone-07-settings-dark")
    shell("cmd", "uimode", "night", "no")
    shell("wm", "size", "2560x1600")
    shell("wm", "density", "320")
    launch()
    capture("large-viewport-01-home-light")
    tap("표시 설정")
    capture("large-viewport-02-settings-light")
finally:
    (OUTPUT / "capture-metadata.json").write_text(json.dumps({
        "source_commit": os.environ.get("SOURCE_SHA", "unknown"),
        "workflow_commit": os.environ.get("GITHUB_SHA", "unknown"),
        "android_api": shell("getprop", "ro.build.version.sdk").strip(),
        "environment": "Android emulator; one phone profile, plus resized large viewport",
        "touch_mirroring_verified": False,
        "physical_foldable_verified": False,
        "captures": records,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    shell("wm", "size", "reset")
    shell("wm", "density", "reset")
    shell("cmd", "uimode", "night", "no")
