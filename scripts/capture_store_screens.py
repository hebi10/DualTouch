"""Capture real emulator UI; this does not validate touch mirroring."""
import hashlib
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

from capture_evidence import controller_focused, controller_resumed, messages_anr_focused
from pathlib import Path

PACKAGE = "com.hebi.dualtouch"
OUTPUT = Path("qa/store-screenshots")
OUTPUT.mkdir(parents=True, exist_ok=True)
records = []
fixture_launcher_recovered = False
fixture_messages_recovered = False

def prepare_fixture():
    # This script changes display configuration and must only run in disposable CI.
    assert os.environ.get("GITHUB_ACTIONS") == "true", "CI fixture only"
    assert shell("getprop", "ro.kernel.qemu").strip() == "1", "Emulator required"
    deadline = time.monotonic() + 60
    while True:
        if (shell("getprop", "sys.boot_completed").strip() == "1" and
            shell("settings", "get", "global", "device_provisioned").strip() == "1" and
            shell("settings", "get", "secure", "user_setup_complete").strip() == "1"):
            break
        if time.monotonic() >= deadline:
            raise AssertionError("CI emulator setup did not finish")
        time.sleep(3)
    time.sleep(5)

def recover_initial_launcher_anr(activity_dump=None):
    global fixture_launcher_recovered
    if records or fixture_launcher_recovered:
        return False
    if os.environ.get("GITHUB_ACTIONS") != "true" or shell("getprop", "ro.kernel.qemu").strip() != "1":
        return False
    if (shell("getprop", "sys.boot_completed").strip() != "1" or
        shell("settings", "get", "global", "device_provisioned").strip() != "1" or
        shell("settings", "get", "secure", "user_setup_complete").strip() != "1"):
        return False
    if shell("settings", "get", "secure", "accessibility_enabled").strip() == "1":
        return False
    # Do not dismiss unknown dialogs, app ANRs, consent, or permission prompts.
    pattern = r"(?m)^\s*mCurrentFocus=Window\{[^\n]*Application Not Responding: com\.google\.android\.apps\.nexuslauncher\}"
    focus = shell("dumpsys", "window", "windows")
    if not re.search(pattern, focus):
        # The final activity diagnostic in R11 included the exact focus line;
        # use that bounded final observation as well, never a generic ANR match.
        if activity_dump is None or not re.search(pattern, activity_dump):
            return False
        # Confirm the focused window is still the exact launcher ANR before
        # acting on the saved observation. Do not use an old screenshot alone.
        fresh = shell("dumpsys", "activity", "activities")
        if not re.search(pattern, fresh):
            return False
    fixture_launcher_recovered = True
    (OUTPUT / "fixture-launcher-anr.png").write_bytes(adb("exec-out", "screencap", "-p"))
    (OUTPUT / "fixture-recovery.json").write_text(json.dumps({
        "source_commit": os.environ.get("SOURCE_SHA", "unknown"),
        "reason": "confirmed Pixel Launcher ANR before initial app capture",
        "action": "force-stop exact launcher package in provisioned CI emulator only"
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    shell("am", "force-stop", "com.google.android.apps.nexuslauncher")
    time.sleep(3)
    launch()
    return True



def adb(*args):
    return subprocess.check_output(["adb", *args], timeout=45)


def shell(*args):
    return adb("shell", *args).decode("utf-8", errors="replace")


def recover_messages_anr(activity_dump):
    global fixture_messages_recovered
    if fixture_messages_recovered or os.environ.get("GITHUB_ACTIONS") != "true":
        return False
    if shell("getprop", "ro.kernel.qemu").strip() != "1":
        return False
    if any(shell(*command).strip() != "1" for command in (
        ("getprop", "sys.boot_completed"),
        ("settings", "get", "global", "device_provisioned"),
        ("settings", "get", "secure", "user_setup_complete"),
    )):
        return False
    if shell("settings", "get", "secure", "accessibility_enabled").strip() == "1":
        return False
    # Require the observed and fresh resumed controller, plus the exact current ANR.
    if not controller_resumed(activity_dump) or not messages_anr_focused(activity_dump):
        return False
    if not controller_resumed(shell("dumpsys", "activity", "activities")):
        return False
    if not messages_anr_focused(shell("dumpsys", "window", "windows")):
        return False
    fixture_messages_recovered = True
    (OUTPUT / "fixture-messages-anr.png").write_bytes(adb("exec-out", "screencap", "-p"))
    (OUTPUT / "fixture-messages-recovery.json").write_text(json.dumps({
        "source_commit": os.environ["SOURCE_SHA"],
        "reason": "confirmed Google Messages ANR over a resumed DualTouch Activity",
        "scope": "provisioned isolated emulator, accessibility disabled, one attempt",
        "action": "force-stop exact fixture package only; no controller relaunch"
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    shell("am", "force-stop", "com.google.android.apps.messaging")
    time.sleep(3)
    return True


def nodes():
    for _ in range(6):
        if recover_initial_launcher_anr():
            time.sleep(2)
        try:
            shell("rm", "-f", "/sdcard/dualtouch-window.xml")
            shell("uiautomator", "dump", "--compressed", "/sdcard/dualtouch-window.xml")
            raw = adb("exec-out", "cat", "/sdcard/dualtouch-window.xml")
            tree = ET.fromstring(raw)
            if (any(n.get("package") == PACKAGE for n in tree.iter("node")) and
                    controller_focused(shell("dumpsys", "window", "windows"))):
                return tree
        except (ET.ParseError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            pass
        time.sleep(2)
    activity = shell("dumpsys", "activity", "activities")
    (OUTPUT / "diagnostic-screen.png").write_bytes(adb("exec-out", "screencap", "-p"))
    (OUTPUT / "diagnostic-activity.txt").write_text(activity, encoding="utf-8")
    (OUTPUT / "diagnostic-crash.txt").write_text(shell("logcat", "-d", "-b", "crash"), encoding="utf-8")
    # Preserve the first failure evidence, then permit the existing one-time
    # guarded launcher recovery if the ANR appeared during the last dump.
    if recover_initial_launcher_anr(activity_dump=activity) or recover_messages_anr(activity):
        return nodes()
    raise AssertionError("App accessibility root or focus was unavailable after bounded retries")


def tap(text):
    for _ in range(8):
        tree = nodes()
        for node in tree.iter("node"):
            if node.get("package") == PACKAGE and node.get("text") == text:
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


def scroll_to_visible_text(text, max_steps=9):
    for _ in range(max_steps):
        tree = nodes()
        if any(node.get("package") == PACKAGE and node.get("text") == text
               and node.get("bounds") not in (None, "[0,0][0,0]") for node in tree.iter("node")):
            return
        width, height = map(int, re.findall(r"(\d+)x(\d+)", shell("wm", "size"))[-1])
        shell("input", "swipe", str(width // 8), str(height * 3 // 4),
              str(width // 8), str(height // 3), "450")
        time.sleep(0.7)
    raise AssertionError("Missing visible section: " + text)


def launch():
    shell("am", "force-stop", PACKAGE)
    started = shell("am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
    print(started, flush=True)
    assert "Error:" not in started, "Activity launch failed"
    time.sleep(2)


def verify_accessibility_settings():
    action = "동의하고 설정 열기"
    tap("나중에")
    assert not any(
        node.get("text") == action for node in nodes().iter("node")
    ), "Dismissal did not return to the app without permission"
    tap("플로팅 표시")
    tap(action)
    for _ in range(10):
        activities = shell("dumpsys", "activity", "activities")
        if re.search(r"(?:topResumedActivity|mResumedActivity).*com\.android\.settings/.*Accessibility", activities):
            shell("input", "keyevent", "KEYCODE_BACK")
            print("Verified: the dialog can be dismissed and Settings opens only after explicit consent", flush=True)
            return
        time.sleep(1)
    raise AssertionError("The Android accessibility settings screen did not open after consent")


def capture(name):
    tree = nodes()
    assert any(n.get("package") == PACKAGE for n in tree.iter("node")), "App UI missing"
    assert controller_focused(shell("dumpsys", "window", "windows")), "Controller not focused before screenshot"
    data = adb("exec-out", "screencap", "-p")
    assert controller_focused(shell("dumpsys", "window", "windows")), "Controller lost focus during screenshot"
    assert data.startswith(b"\x89PNG\r\n\x1a\n"), "Invalid screenshot"
    path = OUTPUT / (name + ".png")
    path.write_bytes(data)
    records.append({"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                    "size": shell("wm", "size").strip(),
                    "density": shell("wm", "density").strip(),
                    "font_scale": shell("settings", "get", "system", "font_scale").strip(),
                    "controller_focused_before_after": True})
    print("Captured:", name, flush=True)


completed = False
original_font = None
try:
    assert subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip() == os.environ["SOURCE_SHA"], "Actual checkout differs from requested source"
    prepare_fixture()
    original_font = shell("settings", "get", "system", "font_scale").strip()
    shell("settings", "put", "system", "font_scale", "1.0")
    shell("wm", "size", "1080x1920")
    shell("wm", "density", "420")
    shell("cmd", "uimode", "night", "no")
    launch()
    capture("phone-01-intro-light")
    scroll_to_visible_text("사용 순서")
    capture("phone-01b-intro-steps-light")
    tap("내용을 이해하고 계속")
    home = nodes()
    home_text = [node.get("text", "") for node in home.iter("node")]
    assert "접근성 권한" in home_text and "설정 필요" in home_text, "Permission progress is missing"
    assert "플로팅 컨트롤" in home_text and "대기" in home_text, "Floating progress is missing"
    assert "분할 앱 연결" in home_text and "연결 필요" in home_text, "Pair progress is missing"
    capture("phone-02-home-light")
    tap("표시 설정")
    capture("phone-03-settings-light")
    shell("input", "swipe", "540", "1550", "540", "450", "450")
    time.sleep(1)
    capture("phone-03b-settings-scroll-light")
    # Capture actual lower settings rather than extrapolating off-screen controls.
    for _ in range(5):
        shell("input", "swipe", "540", "1620", "540", "360", "350")
        time.sleep(0.5)
    capture("phone-03c-settings-bottom-light")
    launch()
    tap("플로팅 표시")
    disclosure = " ".join(node.get("text", "") for node in nodes().iter("node"))
    assert "사용 목적" in disclosure and "일시적으로 확인하는 정보" in disclosure, "Grouped consent disclosure missing"
    capture("phone-04-disclosure-light")
    verify_accessibility_settings()
    tap("분할 화면 안내")
    capture("phone-05-guide-light")
    scroll_to_visible_text("분할 배치 예시")
    capture("phone-05c-guide-layouts-light")
    launch()
    tap("오류·중지 안내")
    capture("phone-05b-help-light")
    scroll_to_visible_text("이전 터치 처리 종료를 확인할 수 없을 때")
    capture("phone-05d-help-recovery-light")
    # 1080x2160 at 420dpi approximates the 412x824dp design viewport.
    shell("wm", "size", "1080x2160")
    launch()
    capture("phone-tall-01-home-light")
    tap("표시 설정")
    capture("phone-tall-02-settings-light")
    shell("wm", "size", "1080x1920")
    shell("cmd", "uimode", "night", "yes")
    launch()
    capture("phone-06-home-dark")
    tap("표시 설정")
    capture("phone-07-settings-dark")
    shell("cmd", "uimode", "night", "no")
    for label, size, density in [("tablet-7", "1600x900", "240"),
                                  ("tablet-10", "2560x1440", "320")]:
        shell("wm", "size", size)
        shell("wm", "density", density)
        launch()
        capture(label + "-01-home-light")
        tap("표시 설정")
        capture(label + "-02-settings-light")

    # Resized emulator viewports only; actual hardware QA remains unverified.
    for label, size, density, scale in [
        ("compact320-font200", "960x1920", "480", "2.0"),
        ("expanded-font200", "1800x1440", "320", "2.0"),
        ("short-landscape", "1800x800", "320", "1.0"),
    ]:
        shell("wm", "size", size)
        shell("wm", "density", density)
        shell("settings", "put", "system", "font_scale", scale)
        launch()
        assert float(shell("settings", "get", "system", "font_scale").strip()) == float(scale)
        scroll_to_visible_text("현재 상태", max_steps=15)
        capture(label + "-home")
        tap("표시 설정")
        scroll_to_visible_text("미리보기", max_steps=15)
        capture(label + "-settings")
        if scale == "2.0":
            scroll_to_visible_text("접근성 사용 동의 철회", max_steps=40)
            capture(label + "-settings-bottom")
            launch()
            scroll_to_visible_text("접근성 권한 안내", max_steps=25)
            tap("접근성 권한 안내")
            dialog_nodes = nodes()
            dialog_texts = {n.get("text") for n in dialog_nodes.iter("node") if n.get("package") == PACKAGE}
            assert {"나중에", "동의하고 설정 열기"} <= dialog_texts, "Consent choices not accessible"
            capture(label + "-disclosure")
            tap("나중에")
            assert not any(n.get("text") == "동의하고 설정 열기" for n in nodes().iter("node")), "Consent dismissal failed"
    completed = True
finally:
    (OUTPUT / "capture-metadata.json").write_text(json.dumps({
        "source_commit": os.environ.get("SOURCE_SHA", "unknown"),
        "workflow_commit": os.environ.get("GITHUB_SHA", "unknown"),
        "checkout_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
        "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT", "unknown"),
        "android_api": shell("getprop", "ro.build.version.sdk").strip(),
        "environment": "Android emulator; 9:16 phone plus resized 16:9 tablet viewports",
        "physical_tablet_verified": False,
        "touch_mirroring_verified": False,
        "physical_foldable_verified": False,
        "completed": completed,
        "captures": records,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    if original_font is not None:
        if original_font == "null":
            shell("settings", "delete", "system", "font_scale")
        else:
            shell("settings", "put", "system", "font_scale", original_font)
        shell("wm", "size", "reset")
        shell("wm", "density", "reset")
        shell("cmd", "uimode", "night", "no")
