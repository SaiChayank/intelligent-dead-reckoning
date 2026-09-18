"""Contract I/O tests only; no raw data, sensors or INS are accessed."""
import io
import json
import math
import subprocess
import sys
import traceback
from dataclasses import replace
from pathlib import Path
import unittest

from contracts.v1.codec import (ContractError, Limits, decode_json, encode_json, read_json,
                                write_json, read_jsonl, write_jsonl, validate_rotation)
from contracts.v1.models import *

ROOT = Path(__file__).resolve().parents[1] / "contracts/v1"


class ContractCodecTest(unittest.TestCase):
    def setUp(self):
        self.raw = (ROOT / "golden_records.jsonl").read_bytes()
        self.records = list(read_jsonl(io.BytesIO(self.raw)))

    def test_all_event_types_typed_and_round_trip_both_formats(self):
        self.assertEqual({r.event.data.TYPE for r in self.records},
                         {"imu", "gnss", "calibration", "navigation", "gnss_quality", "confidence", "diagnostic"})
        for r in self.records:
            self.assertEqual(decode_json(encode_json(r)), r)
            stream = io.BytesIO()
            write_json(r, stream)
            self.assertFalse(stream.closed)
            stream.seek(0)
            self.assertEqual(read_json(stream), r)
        stream = io.BytesIO()
        write_jsonl(iter(self.records), stream)
        stream.seek(0)
        self.assertEqual(list(read_jsonl(stream)), self.records)

    def test_shared_negative_corpus_is_rejected_with_exact_codes(self):
        for case in json.loads((ROOT / "invalid_records.json").read_text(encoding="utf-8")):
            with self.subTest(case=case["name"]):
                with self.assertRaises(ContractError) as raised:
                    decode_json(case["input"].encode("utf-8"))
                self.assertEqual(raised.exception.code, case["code"])

    def test_optional_fields_present_as_null_are_preserved(self):
        fix = self.records[2].event.data
        self.assertIsInstance(fix, GnssMeasurement)
        self.assertIsNone(fix.speed_m_s)
        self.assertIsNone(fix.bearing_deg)
        raw = json.loads(encode_json(self.records[2]))
        self.assertIn("speed_m_s", raw["event"]["data"])
        self.assertIsNone(raw["event"]["data"]["speed_m_s"])

    def test_int64_boundaries_without_double_conversion(self):
        r = self.records[0]
        for value in (0, 2**53+1, 2**63-1):
            changed = replace(r, event=replace(r.event, t_ns=value, received_ns=value))
            wire = encode_json(changed)
            self.assertEqual(decode_json(wire).event.t_ns, value)
            self.assertEqual(json.loads(wire)["event"]["t_ns"], str(value))
        with self.assertRaises(ContractError):
            encode_json(replace(r, event=replace(r.event, t_ns=2**63)))

    def test_writer_rejects_invalid_typed_values_before_emitting_record(self):
        r = self.records[0]
        for xyz in (Vector3(math.nan,0,1), Vector3(math.inf,0,1)):
            bad = replace(r, event=replace(r.event, data=replace(r.event.data, xyz=xyz)))
            stream = io.BytesIO()
            with self.assertRaises(ContractError):
                write_json(bad, stream)
            self.assertEqual(stream.getvalue(), b"")
        bad = replace(r, event=replace(r.event, data=replace(r.event.data, sensor="accelerometer")))
        with self.assertRaises(ContractError):
            encode_json(bad)

    def test_rotations_reject_reflections_and_do_not_normalize_quaternions(self):
        validate_rotation([[0,-1,0],[1,0,0],[0,0,1]])
        for matrix in ([[-1,0,0],[0,1,0],[0,0,1]], [[2,0,0],[0,1,0],[0,0,1]], [[float("nan"),0,0],[0,1,0],[0,0,1]]):
            with self.assertRaises(ContractError):
                validate_rotation(matrix)
        r = self.records[3]
        good = replace(r, event=replace(r.event, data=replace(r.event.data, q_vehicle_from_device_wxyz=Quaternion(-1,0,0,0))))
        self.assertEqual(decode_json(encode_json(good)), good)
        bad = replace(r, event=replace(r.event, data=replace(r.event.data, q_vehicle_from_device_wxyz=Quaternion(2,0,0,0))))
        with self.assertRaises(ContractError):
            encode_json(bad)

    def test_duplicate_ids_fail_with_line_and_preserve_valid_prefix(self):
        one = encode_json(self.records[0])+b"\n"
        reader = read_jsonl(io.BytesIO(one+one))
        self.assertEqual(next(reader), self.records[0])
        with self.assertRaises(ContractError) as raised:
            next(reader)
        self.assertEqual((raised.exception.code, raised.exception.line), ("DUPLICATE_EVENT", 2))
        stream = io.BytesIO()
        with self.assertRaises(ContractError):
            write_jsonl([self.records[0], self.records[0]], stream)
        self.assertEqual(stream.getvalue(), one)

    def test_same_time_different_sensors_and_out_of_order_events_are_not_lost(self):
        a, b = self.records[:2]
        b = replace(b, event=replace(b.event, t_ns=a.event.t_ns, received_ns=a.event.received_ns))
        out = io.BytesIO()
        write_jsonl([b, a], out)
        self.assertEqual(list(read_jsonl(io.BytesIO(out.getvalue()))), [b,a])

    def test_session_source_and_initialization_mode_cannot_change_silently(self):
        a = self.records[0]
        for header in (replace(a.header, session_id="other"), replace(a.header, source=Source.REAL)):
            b = replace(self.records[1], header=header)
            with self.assertRaises(ContractError) as error:
                list(read_jsonl(io.BytesIO(encode_json(a)+b"\n"+encode_json(b))))
            self.assertEqual(error.exception.code, "SESSION_MISMATCH")
        nav = self.records[4]
        changed = replace(nav, event=replace(nav.event, event_id="90", data=replace(nav.event.data, initialization_mode=InitializationMode.EVALUATION)))
        with self.assertRaises(ContractError):
            write_jsonl([nav, changed], io.BytesIO())

    def test_truncated_blank_invalid_utf8_and_crlf(self):
        one = encode_json(self.records[0])
        self.assertEqual(list(read_jsonl(io.BytesIO(one))), [self.records[0]])
        self.assertEqual(list(read_jsonl(io.BytesIO(one+b"\r\n"))), [self.records[0]])
        for bad in (one[:-1], b"\n", b"\xff"):
            with self.assertRaises(ContractError) as raised:
                list(read_jsonl(io.BytesIO(one+b"\n"+bad)))
            self.assertEqual(raised.exception.line, 2)
        with self.assertRaises(ContractError) as error:
            decode_json(b"\xff")
        self.assertEqual(error.exception.code, "INVALID_UTF8")

    def test_laziness_record_size_event_count_and_depth_limits(self):
        stream = io.BytesIO(self.raw)
        rows = read_jsonl(stream)
        self.assertEqual(stream.tell(), 0)
        next(rows)
        self.assertLess(stream.tell(), len(self.raw))
        with self.assertRaises(ContractError) as error:
            list(read_jsonl(io.BytesIO(self.raw), Limits(max_records=1)))
        self.assertEqual(error.exception.code, "RESOURCE_LIMIT")
        for data in (b" "*1000, b"["*32+b"0"+b"]"*32):
            with self.assertRaises(ContractError) as error:
                decode_json(data, Limits(max_record_bytes=100))
            self.assertEqual(error.exception.code, "RESOURCE_LIMIT")

    def test_short_reads_and_io_failures_are_explicit(self):
        class Short(io.BytesIO):
            def read(self, n=-1):
                return super().read(min(n, 3))
        class Broken(io.BytesIO):
            def write(self, value):
                raise OSError("private path must not leak")
            def read(self, n=-1):
                raise OSError("private path must not leak")
        self.assertEqual(read_json(Short(encode_json(self.records[0]))), self.records[0])
        for action in (lambda: read_json(Broken()), lambda: write_json(self.records[0], Broken())):
            try:
                action()
                self.fail("Expected I/O failure")
            except ContractError as error:
                self.assertEqual(error.code, "IO_ERROR")
                self.assertNotIn("private path must not leak", "".join(traceback.format_exception(error)))

    def test_all_nullable_fields_can_be_absent_values_not_absent_keys(self):
        from contracts.v1.codec import SPECS
        for kind, (model, rules) in SPECS.items():
            r = next(r for r in self.records if r.event.data.TYPE == kind)
            d = json.loads(encode_json(r))
            for key, rule in rules.items():
                if rule.endswith("?"):
                    d["event"]["data"][key] = None
            if kind == "calibration": d["event"]["data"]["status"] = "pending"
            if kind == "navigation": d["event"]["data"]["status"] = "uninitialized"
            parsed = decode_json(json.dumps(d).encode())
            self.assertEqual(decode_json(encode_json(parsed)), parsed)

    def test_edge_fixtures_and_every_source_label_round_trip(self):
        edge = list(read_jsonl(io.BytesIO((ROOT / "edge_records.jsonl").read_bytes())))
        self.assertEqual(len(edge), 9)
        self.assertEqual(edge[7].event.data.dropped_count, 2**63-1)
        self.assertEqual(edge[-1].event.t_ns, 2**63-1)
        for source in Source:
            for r in edge:
                changed = replace(r, header=replace(r.header, source=source))
                self.assertEqual(decode_json(encode_json(changed)), changed)

    def test_imports_do_not_perform_application_io(self):
        script = '''
from unittest.mock import patch
from pathlib import Path
import builtins
forbidden = AssertionError("Contract import performed application I/O")
with patch.object(builtins, "open", side_effect=forbidden), \\
     patch.object(Path, "open", side_effect=forbidden), \\
     patch.object(Path, "mkdir", side_effect=forbidden):
    import contracts.v1.models
    import contracts.v1.codec
    import contracts.v1.interop
'''
        result = subprocess.run([sys.executable, "-B", "-c", script],
                                cwd=ROOT.parents[1], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
