import importlib.util
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

MODULE = Path(__file__).resolve().parents[1] / "instrumentation_to_junit.py"
spec = importlib.util.spec_from_file_location("instrumentation_to_junit", MODULE)
parser = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = parser
spec.loader.exec_module(parser)


def status(code, name="works", current=1, total=1, stack=""):
    return (f"INSTRUMENTATION_STATUS: class=example.Tests\n"
            f"INSTRUMENTATION_STATUS: current={current}\n"
            f"INSTRUMENTATION_STATUS: numtests={total}\n"
            f"INSTRUMENTATION_STATUS: test={name}\n"
            + (f"INSTRUMENTATION_STATUS: stack={stack}\n" if stack else "")
            + f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def footer(total=1):
    return f"INSTRUMENTATION_RESULT: stream=\nTime: 1.25\nOK ({total} tests)\nINSTRUMENTATION_CODE: -1\n"


class InstrumentationEvidenceTest(unittest.TestCase):
    def test_success_and_assumption_are_distinct_and_xml_safe(self):
        text = (status(1, total=2) + status(0, total=2)
                + status(1, "needsDevice", 2, 2)
                + status(-4, "needsDevice", 2, 2, "Assumption <device> & consent\n at Tests.kt:3") + footer(2))
        xml, result = parser.reports(text, "0" * 64)
        self.assertEqual((2, 1, 1, 0, 0), tuple(result[key] for key in ("tests", "passed", "skipped", "failures", "errors")))
        self.assertTrue(result["successful"])
        reparsed = ET.fromstring(ET.tostring(xml))
        self.assertEqual("assumption", reparsed.find("testcase/skipped").attrib["type"])
        self.assertNotIn("hostname", xml.attrib)

    def test_truncation_and_crash_cannot_be_green(self):
        for text in (status(1), status(1) + footer(), status(1) + "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"):
            with self.subTest(text=text), self.assertRaises(parser.InvalidInstrumentation):
                parser.parse(text)

    def test_duplicate_terminal_is_rejected(self):
        with self.assertRaisesRegex(parser.InvalidInstrumentation, "Duplicate terminal"):
            parser.parse(status(1) + status(0) + status(0) + footer())

    def test_footer_and_numtests_must_agree(self):
        for text in (status(1) + status(0) + footer(2),
                     status(1) + status(0, total=2) + footer()):
            with self.subTest(text=text), self.assertRaises(parser.InvalidInstrumentation):
                parser.parse(text)

    def test_complete_failure_produces_red_report(self):
        text = (status(1) + status(-2, stack="java.lang.AssertionError: wrong")
                + "INSTRUMENTATION_RESULT: stream=\nTime: 1.25\nFAILURES!!!\nTests run: 1,  Failures: 1\nINSTRUMENTATION_CODE: -1\n")
        xml, result = parser.reports(text, "0" * 64)
        self.assertFalse(result["successful"])
        self.assertEqual(1, result["failures"])
        self.assertIsNotNone(xml.find("testcase/failure"))

    def test_lying_green_footer_is_rejected(self):
        with self.assertRaises(parser.InvalidInstrumentation):
            parser.parse(status(1) + status(-1, stack="linkage failure") + footer())

    def test_junit_ignore_does_not_require_start_event(self):
        _, result = parser.reports(status(-3) + footer(), "0" * 64)
        self.assertEqual(1, result["skipped"])
        self.assertEqual(0, result["passed"])


if __name__ == "__main__":
    unittest.main()
