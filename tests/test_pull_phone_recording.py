import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
import pull_phone_recording as pull  # noqa: E402


class PullPhoneRecordingTest(unittest.TestCase):
    def test_listing_drops_the_bundled_demo_sessions_and_anything_that_is_not_a_recording_id(self):
        listing = "urban-canyon-multipath\nhyderabad-demo-drive\n125c75bc-4ade-453e-94a0-e52a32cf0a22\n.hidden\nstop-and-go-outage\n"
        with mock.patch.object(pull, "adb", return_value=listing):
            self.assertEqual(pull.list_recordings(["adb"]), ["125c75bc-4ade-453e-94a0-e52a32cf0a22"])

    def test_pull_writes_both_files_byte_for_byte_and_refuses_an_existing_folder(self):
        payload = {"metadata.json": b'{"a":1}\n', "measurements.jsonl": b"\x00\xff line\r\n"}

        def fake_adb(base, *args, binary=False):
            return payload[args[-1].rsplit("/", 1)[1]]

        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(pull, "adb", side_effect=fake_adb):
            target = Path(tmp) / "label" / "rec"
            pull.pull(["adb"], "rec", target)
            for name, data in payload.items():
                self.assertEqual((target / name).read_bytes(), data)
            with self.assertRaises(FileExistsError):
                pull.pull(["adb"], "rec", target)

    def test_a_label_that_could_escape_the_folder_is_rejected(self):
        with mock.patch.object(pull, "adb_base", return_value=["adb"]), \
                mock.patch.object(pull, "list_recordings", return_value=["125c75bc-4ade-453e-94a0-e52a32cf0a22"]):
            for label in ("../x", "a/b", "", "with space"):
                with self.assertRaises(SystemExit, msg=label):
                    pull.main(["--label", label])

    def test_two_devices_without_a_serial_is_an_error_not_a_guess(self):
        completed = mock.Mock(stdout="List of devices attached\nAAA\tdevice\nBBB\tdevice\n")
        with mock.patch.object(pull, "find_adb", return_value="adb"), mock.patch.object(pull.subprocess, "run", return_value=completed):
            with self.assertRaises(SystemExit) as caught:
                pull.adb_base(None)
        self.assertIn("pass --serial", str(caught.exception))


if __name__ == "__main__":
    unittest.main()
