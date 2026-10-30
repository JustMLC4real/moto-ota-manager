#!/usr/bin/env python3
"""Convert complete `am instrument -w -r` output to strict, auditable JUnit XML.

ADB's process exit status is not a test result. Incomplete, contradictory or
crashed runner output is rejected; a complete failed run produces red reports
and a nonzero exit status. No hostname or local log path is put in the report.
"""

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


class InvalidInstrumentation(ValueError):
    pass


@dataclass(frozen=True)
class TestResult:
    classname: str
    name: str
    current: int
    code: int
    stack: str


def parse(text):
    """Return terminal records and measured runner duration; never infer passes."""
    if re.search(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", text):
        raise InvalidInstrumentation("Control characters in runner output")
    if re.search(r"(?im)^(?:INSTRUMENTATION_(?:FAILED|ABORTED|STATUS_ERROR):|.*Process crashed\b)", text):
        raise InvalidInstrumentation("Runner failed or crashed")
    block = {}
    last_key = None
    expected = None
    starts = {}
    results = {}
    runner_codes = []
    for line in text.splitlines():
        field = re.fullmatch(r"INSTRUMENTATION_STATUS: ([^=]+)=(.*)", line)
        if field:
            key, value = field.groups()
            if key in block:
                raise InvalidInstrumentation("Duplicate field in status record: " + key)
            block[key] = value
            last_key = key
            continue
        status = re.fullmatch(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", line)
        if status:
            code = int(status[1])
            if code not in {1, 0, -1, -2, -3, -4}:
                raise InvalidInstrumentation("Unknown test status code")
            try:
                classname, name = block["class"], block["test"]
                current, total = int(block["current"]), int(block["numtests"])
            except (KeyError, ValueError) as error:
                raise InvalidInstrumentation("Incomplete test status record") from error
            if not classname or not name or total < 1 or not 1 <= current <= total:
                raise InvalidInstrumentation("Invalid test identity or count")
            if expected is None:
                expected = total
            if expected != total:
                raise InvalidInstrumentation("numtests changed during run")
            identity = (classname, name)
            if code == 1:
                if identity in starts or identity in results or current in starts.values():
                    raise InvalidInstrumentation("Duplicate test start")
                starts[identity] = current
            else:
                if identity in results:
                    raise InvalidInstrumentation("Duplicate terminal test record")
                # JUnit @Ignore may emit only an ignored terminal record.
                if code != -3 and starts.get(identity) != current:
                    raise InvalidInstrumentation("Terminal record has no matching start")
                if identity in starts and starts[identity] != current:
                    raise InvalidInstrumentation("Test ordinal changed")
                if any(result.current == current for result in results.values()):
                    raise InvalidInstrumentation("Duplicate terminal ordinal")
                results[identity] = TestResult(classname, name, current, code, block.get("stack", ""))
            block = {}
            last_key = None
            continue
        runner_code = re.fullmatch(r"INSTRUMENTATION_CODE: (-?\d+)", line)
        if runner_code:
            runner_codes.append(int(runner_code[1]))
        if line.startswith("INSTRUMENTATION_"):
            last_key = None
        elif last_key:
            block[last_key] += "\n" + line
    if block or runner_codes != [-1]:
        raise InvalidInstrumentation("Incomplete status block or missing successful runner termination")
    if expected is None or len(results) != expected or set(starts) - set(results):
        raise InvalidInstrumentation("Missing terminal test records")
    if {result.current for result in results.values()} != set(range(1, expected + 1)):
        raise InvalidInstrumentation("Test ordinals do not cover numtests")
    success_footers = re.findall(r"(?m)^OK \((\d+) tests?\)\s*$", text)
    failed_footer = re.search(r"(?m)^FAILURES!!!\s*$", text)
    failed = any(result.code in {-1, -2} for result in results.values())
    if failed:
        if success_footers or not failed_footer:
            raise InvalidInstrumentation("Failed tests disagree with runner footer")
        counts = re.findall(r"(?m)^Tests run: (\d+),\s+Failures: (\d+)(?:,\s+Errors: (\d+))?\s*$", text)
        if len(counts) != 1 or int(counts[0][0]) != expected:
            raise InvalidInstrumentation("Failed runner footer count does not match records")
        if int(counts[0][1]) + int(counts[0][2] or 0) != sum(result.code in {-1, -2} for result in results.values()):
            raise InvalidInstrumentation("Failure footer count does not match records")
    elif failed_footer or success_footers != [str(expected)]:
        raise InvalidInstrumentation("Successful terminal records disagree with runner footer")
    # JUnit formats this field with the device locale, including Dutch commas.
    times = re.findall(r"(?m)^Time: (\d+(?:[.,]\d+)?)\s*$", text)
    if len(times) != 1:
        raise InvalidInstrumentation("Missing or ambiguous measured runner duration")
    return sorted(results.values(), key=lambda result: result.current), times[0].replace(",", ".")


def reports(text, source_sha256):
    results, duration = parse(text)
    codes = Counter(result.code for result in results)
    counts = {"tests": len(results), "passed": codes[0], "failures": codes[-2],
              "errors": codes[-1], "skipped": codes[-3] + codes[-4]}
    summary = {"schemaVersion": 1, "sourceLogSha256": source_sha256,
               "complete": True, "successful": not (counts["failures"] or counts["errors"]),
               **counts, "runnerSeconds": float(duration),
               "terminalStatusCounts": {str(key): value for key, value in sorted(codes.items())},
               "skippedTests": [{"class": result.classname, "test": result.name,
                                 "kind": "assumption" if result.code == -4 else "ignored"}
                                for result in results if result.code in {-3, -4}]}
    suite = ET.Element("testsuite", {"name": "AndroidJUnitRunner", "tests": str(counts["tests"]),
                                    "failures": str(counts["failures"]), "errors": str(counts["errors"]),
                                    "skipped": str(counts["skipped"]), "time": duration})
    properties = ET.SubElement(suite, "properties")
    ET.SubElement(properties, "property", {"name": "sourceLogSha256", "value": source_sha256})
    for result in results:
        case = ET.SubElement(suite, "testcase", {"classname": result.classname, "name": result.name})
        tag = {0: None, -1: "error", -2: "failure", -3: "skipped", -4: "skipped"}[result.code]
        if tag:
            reason = result.stack.splitlines()[0] if result.stack else {"error": "Runner error", "failure": "Test failure", "skipped": "Assumption or ignored"}[tag]
            detail = ET.SubElement(case, tag, {"message": reason, "type": { -1: "instrumentation-error", -2: "assertion-failure", -3: "ignored", -4: "assumption"}[result.code]})
            detail.text = result.stack
    ET.indent(suite, space="  ")
    return suite, summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--xml", type=Path, required=True)
    parser.add_argument("--summary", type=Path, required=True)
    args = parser.parse_args()
    try:
        raw = args.log.read_bytes()
        suite, summary = reports(raw.decode("utf-8-sig"), hashlib.sha256(raw).hexdigest())
        for output in (args.xml, args.summary):
            output.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(suite).write(args.xml, encoding="utf-8", xml_declaration=True)
        args.summary.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summary, indent=2))
        return 0 if summary["successful"] else 1
    except (InvalidInstrumentation, UnicodeError, OSError) as error:
        print("Invalid instrumentation evidence: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
