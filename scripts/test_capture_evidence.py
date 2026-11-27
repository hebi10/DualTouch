"""Transport integrity regression; no ADB, keys, device, or network."""
import hashlib
import struct
import subprocess
import unittest
from unittest.mock import Mock
from capture_evidence import PNG, read_verified_png

# Only the header matters here; the expected digest binds the full byte sequence.
DATA = PNG + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 1680, 1440) + bytes(9)
DIGEST = hashlib.sha256(DATA).hexdigest()

class CaptureEvidenceTest(unittest.TestCase):
    def test_valid_bytes_returned_once(self):
        read = Mock(return_value=DATA)
        self.assertEqual(read_verified_png(read, "screen.png", DIGEST, 1680, 1440, lambda _: None), DATA)
        self.assertEqual(read.call_count, 1)

    def test_transient_bad_bytes_can_be_recovered_without_new_capture(self):
        read = Mock(side_effect=[b"run-as: error", subprocess.TimeoutExpired("adb", 1), DATA])
        self.assertEqual(read_verified_png(read, "screen.png", DIGEST, 1680, 1440, lambda _: None), DATA)
        self.assertEqual(read.call_count, 3)

    def test_wrong_hash_never_passes(self):
        read = Mock(return_value=DATA + b"changed")
        with self.assertRaises(AssertionError):
            read_verified_png(read, "screen.png", DIGEST, 1680, 1440, lambda _: None)
        self.assertEqual(read.call_count, 3)

    def test_wrong_size_never_passes_even_with_matching_hash(self):
        with self.assertRaises(AssertionError):
            read_verified_png(lambda _: DATA, "screen.png", DIGEST, 1, 1, lambda _: None)

if __name__ == "__main__":
    unittest.main()
