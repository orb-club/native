"""Measurement validation must fail closed on partial or malformed device logs."""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PARSER = Path(__file__).with_name("analyze_log.py")


def snapshot(phase, count):
    return f"""I/OrbJniProbe: SNAPSHOT_BEGIN {phase}
I/art: global reference table dump:
I/art:   Last 10 entries (of {count}):
I/art: weak global table dump:
I/art:   Last 10 entries (of 999):
I/OrbJniProbe: SNAPSHOT_END {phase}
"""


def registration(leak=0):
    return ("I/OrbJniProbe: MODE registrationOnly=true\n"
            + snapshot("engine_baseline", 350)
            + "".join(snapshot(f"engine_{i}", 386 + leak * (i - 1)) for i in range(1, 31))
            + "I/OrbJniProbe: ENGINE_PROBE_COMPLETED\n")


class ReferenceLogTest(unittest.TestCase):
    def invoke(self, log, mode="registration", *extra):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "log.txt"
            path.write_text(log)
            return subprocess.run([sys.executable, str(PARSER), str(path), "--mode", mode, *extra],
                                  capture_output=True, text=True)

    def test_flat_strong_references_pass_ignoring_weak_table(self):
        self.assertEqual(self.invoke(registration()).returncode, 0)

    def test_leak_fails_unless_explicit_negative_control(self):
        log = registration(6)
        self.assertNotEqual(self.invoke(log).returncode, 0)
        self.assertEqual(self.invoke(log, "registration", "--expect-leak").returncode, 0)

    def test_partial_run_cannot_pass(self):
        self.assertNotEqual(self.invoke(registration().replace("ENGINE_PROBE_COMPLETED", "")).returncode, 0)
        self.assertNotEqual(self.invoke(registration().replace(snapshot("engine_17", 386), "")).returncode, 0)

    def test_failure_overrides_completion(self):
        self.assertNotEqual(self.invoke(registration() + "ENGINE_PROBE_FAILED timeout").returncode, 0)

    def test_duplicate_runs_cannot_pass(self):
        self.assertNotEqual(self.invoke(registration() + registration()).returncode, 0)

    def test_wrong_mode_cannot_pass(self):
        self.assertNotEqual(self.invoke(registration().replace("registrationOnly=true", "registrationOnly=false")).returncode, 0)

    def test_registration_cannot_masquerade_as_sentry_engine_run(self):
        self.assertNotEqual(self.invoke(registration(), "engine").returncode, 0)
        self.assertNotEqual(self.invoke(registration().replace("MODE registrationOnly=true", ""), "engine").returncode, 0)
        self.assertEqual(self.invoke(registration().replace("MODE registrationOnly=true", "MODE registrationOnly=false"), "engine").returncode, 0)

    def test_no_strong_global_measurement_cannot_pass(self):
        self.assertNotEqual(self.invoke(registration().replace("global reference table", "local reference table")).returncode, 0)

    def test_first_use_allocates_exactly_one_set(self):
        log = snapshot("engine_baseline", 350) + snapshot("first_use_0", 356) + snapshot("first_use_1", 356)
        log += "FIRST_USE_PROBE_COMPLETED"
        self.assertEqual(self.invoke(log, "first-use").returncode, 0)
        self.assertNotEqual(self.invoke(log.replace("of 356", "of 362"), "first-use").returncode, 0)

    def test_sdk_control_must_detect_held_references(self):
        log = snapshot("control_before", 400) + snapshot("control_held_32", 432) + snapshot("control_released", 400)
        log += snapshot("cycle_1", 400)
        log += "".join(snapshot(f"cycle_{i}", 400) for i in range(10, 101, 10))
        log += "PROBE_COMPLETED"
        self.assertEqual(self.invoke(log, "sdk").returncode, 0)
        self.assertNotEqual(self.invoke(log.replace("of 432", "of 431"), "sdk").returncode, 0)


if __name__ == "__main__":
    unittest.main()
