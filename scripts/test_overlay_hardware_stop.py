import subprocess
import unittest
from unittest.mock import Mock

from overlay_hardware_stop import console_result, press_and_release


class HardwareStopTransportTests(unittest.TestCase):
    def test_numeric_events_use_explicit_emulator_and_sync(self):
        run = Mock(return_value=subprocess.CompletedProcess([], 0, b" OK\r\n"))
        evidence = press_and_release("emulator-5554", run=run, sleep=Mock())
        self.assertTrue(evidence["press_ok"])
        self.assertTrue(evidence["release_ok"])
        self.assertEqual([call.args[0] for call in run.call_args_list], [
            ["adb", "-s", "emulator-5554", "emu", "event", "send", "1:114:1", "0:0:0"],
            ["adb", "-s", "emulator-5554", "emu", "event", "send", "1:114:0", "0:0:0"],
        ])

    def test_acknowledgement_is_not_delivery(self):
        self.assertTrue(console_result(0, "")["accepted"])
        self.assertEqual(console_result(0, "")["acknowledgement"], "silent")
        self.assertFalse(console_result(0, "KO: invalid event\nOK")["accepted"])
        self.assertFalse(console_result(1, "OK")["accepted"])
        self.assertFalse(console_result(0, "unexpected")["accepted"])

    def test_rejected_press_is_not_retried_and_release_still_runs(self):
        run = Mock(side_effect=[
            subprocess.CompletedProcess([], 0, b"KO: invalid event"),
            subprocess.CompletedProcess([], 0, b"OK"),
        ])
        evidence = press_and_release("emulator-5554", run=run, sleep=Mock())
        self.assertFalse(evidence["press_ok"])
        self.assertTrue(evidence["release_ok"])
        self.assertEqual(run.call_count, 2)

    def test_timeout_is_preserved_and_release_still_runs(self):
        run = Mock(side_effect=[
            subprocess.TimeoutExpired("adb", 3),
            subprocess.CompletedProcess([], 0, b"OK"),
        ])
        evidence = press_and_release("emulator-5554", run=run, sleep=Mock())
        self.assertEqual(evidence["press"]["error"], "TimeoutExpired")
        self.assertTrue(evidence["release_ok"])
        self.assertEqual(run.call_count, 2)

    def test_release_failure_is_not_success(self):
        run = Mock(side_effect=[
            subprocess.CompletedProcess([], 0, b"OK"),
            subprocess.CompletedProcess([], 1, b"KO"),
        ])
        evidence = press_and_release("emulator-5554", run=run, sleep=Mock())
        self.assertTrue(evidence["press_ok"])
        self.assertFalse(evidence["release_ok"])

    def test_non_emulator_is_rejected_before_sending_events(self):
        run = Mock()
        with self.assertRaises(ValueError):
            press_and_release("physical-device", run=run, sleep=Mock())
        run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
