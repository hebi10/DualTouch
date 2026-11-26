import json
import subprocess
import unittest
from unittest.mock import Mock

from overlay_hardware_stop import (
    press_and_release, classify_guest_volume_events, observe_guest_volume_events,
)


class HardwareStopTransportTests(unittest.TestCase):
    def test_evdev_events_use_the_loopback_emulator_controller(self):
        run = Mock(return_value=subprocess.CompletedProcess([], 0))
        evidence = press_and_release("emulator-5554", "/sdk/emulator/lib", run=run, sleep=Mock())
        self.assertTrue(evidence["press_ok"])
        self.assertTrue(evidence["release_ok"])
        commands = [call.args[0] for call in run.call_args_list]
        for command, event_type in zip(commands, ("keydown", "keyup"), strict=True):
            self.assertEqual(command[:8], [
                "grpcurl", "-plaintext", "-max-time", "2", "-import-path",
                "/sdk/emulator/lib", "-proto", "emulator_controller.proto",
            ])
            self.assertEqual(json.loads(command[9]), {
                "codeType": "Evdev", "eventType": event_type, "keyCode": 114,
            })
            self.assertEqual(command[10:], [
                "127.0.0.1:8556", "android.emulation.control.EmulatorController/sendKey",
            ])

    def test_rejected_press_is_not_retried_and_release_still_runs(self):
        run = Mock(side_effect=[
            subprocess.CompletedProcess([], 1),
            subprocess.CompletedProcess([], 0),
        ])
        evidence = press_and_release("emulator-5554", "/sdk/emulator/lib", run=run, sleep=Mock())
        self.assertFalse(evidence["press_ok"])
        self.assertTrue(evidence["release_ok"])
        self.assertEqual(run.call_count, 2)

    def test_timeout_is_preserved_and_release_still_runs(self):
        run = Mock(side_effect=[
            subprocess.TimeoutExpired("grpcurl", 3),
            subprocess.CompletedProcess([], 0),
        ])
        evidence = press_and_release("emulator-5554", "/sdk/emulator/lib", run=run, sleep=Mock())
        self.assertEqual(evidence["press"]["error"], "TimeoutExpired")
        self.assertTrue(evidence["release_ok"])
        self.assertEqual(run.call_count, 2)

    def test_release_failure_is_not_success(self):
        run = Mock(side_effect=[
            subprocess.CompletedProcess([], 0),
            subprocess.CompletedProcess([], 1),
        ])
        evidence = press_and_release("emulator-5554", "/sdk/emulator/lib", run=run, sleep=Mock())
        self.assertTrue(evidence["press_ok"])
        self.assertFalse(evidence["release_ok"])

    def test_non_emulator_is_rejected_before_sending_events(self):
        run = Mock()
        with self.assertRaises(ValueError):
            press_and_release("physical-device", "/sdk/emulator/lib", run=run, sleep=Mock())
        run.assert_not_called()

    def test_guest_volume_counts_ignore_unrelated_events(self):
        result = classify_guest_volume_events(124, """
[  123.000] /dev/input/event0: EV_KEY KEY_VOLUMEDOWN DOWN
[  123.100] /dev/input/event0: EV_SYN SYN_REPORT 00000000
[  123.200] /dev/input/event0: EV_KEY KEY_VOLUMEUP DOWN
[  123.300] /dev/input/event0: EV_KEY KEY_VOLUMEDOWN UP
""")
        self.assertEqual(result["outcome"], "volume_event_observed")
        self.assertEqual((result["down_count"], result["up_count"]), (1, 1))
        self.assertFalse(result["observation_incomplete"])

    def test_unprivileged_trace_failure_is_not_no_delivery(self):
        result = classify_guest_volume_events(1, "could not open /dev/input/event0, Permission denied")
        self.assertEqual(result["outcome"], "observer_unavailable")
        self.assertTrue(result["observation_incomplete"])

    def test_empty_trace_is_inconclusive(self):
        result = classify_guest_volume_events(124, "")
        self.assertEqual(result["outcome"], "not_observed_in_available_stream")
        self.assertEqual(result["down_count"], 0)
        self.assertEqual(result["up_count"], 0)

    def test_single_press_and_release_trial_with_read_only_observer(self):
        process = Mock(returncode=124)
        process.poll.return_value = None
        process.communicate.return_value = (b"EV_KEY KEY_VOLUMEDOWN DOWN\nEV_KEY KEY_VOLUMEDOWN UP\n", None)
        popen = Mock(return_value=process)
        send = Mock(return_value={"press_ok": True, "release_ok": True})
        command, trace = observe_guest_volume_events("emulator-5554", send, popen=popen, sleep=Mock())
        send.assert_called_once_with()
        self.assertEqual(trace["down_count"], 1)
        self.assertEqual(trace["up_count"], 1)
        self.assertEqual(popen.call_args.args[0], [
            "adb", "-s", "emulator-5554", "shell", "timeout", "3", "getevent", "-lt"
        ])
        self.assertTrue(command["press_ok"])

    def test_observer_unavailable_does_not_retry_the_stop_request(self):
        send = Mock(return_value={"press_ok": False, "release_ok": True})
        command, trace = observe_guest_volume_events("emulator-5554", send,
            popen=Mock(side_effect=OSError("missing")), sleep=Mock())
        send.assert_called_once_with()
        self.assertEqual(trace["outcome"], "observer_unavailable")
        self.assertFalse(command["press_ok"])


if __name__ == "__main__":
    unittest.main()
