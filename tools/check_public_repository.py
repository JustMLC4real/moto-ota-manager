"""Local public-source hygiene checks. Never prints suspected secret values.

Examines the checkout snapshot (tracked plus non-ignored new files), not Git
history or image pixels. A pass is not a credential, license or privacy guarantee.
"""
import argparse
import json
import re
import subprocess
from pathlib import Path, PurePosixPath

FORBIDDEN_DIRECTORIES = {'.git', '.gradle', '.cxx', '.externalNativeBuild', 'build', '.secrets', 'secrets', 'signing'}
FORBIDDEN_SUFFIXES = {'.apk', '.aab', '.jks', '.keystore', '.p12', '.pfx', '.img', '.bin', '.gz', '.zip', '.hprof'}
SENSITIVE_ASSIGNMENT = re.compile(r'(?im)^\s*(?:MOTO_RELEASE_(?:STORE_PASSWORD|KEY_PASSWORD|KEYSTORE_BASE64)|(?:ro\.(?:boot\.)?)?serialno|(?:device_)?imei)\s*[=:]\s*([^\r\n]+)')
PRIVATE_KEY = re.compile(rb'(?m)^-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\r?$')
SIGNED_QUERY = re.compile(r'https://[^\s"<>\x27]+[?&](?:token|signature|x-amz-signature|auth)=([^&#\s"<>\x27]+)', re.I)
SYNTHETIC = re.compile(r'(?:secret|fake|synthetic|test|private-test-token|must-not-escape|hidden|signed-secret|signed-url-secret|verysecret)(?:[-_0-9a-z]*)', re.I)


def inspect(root):
    process = subprocess.run(['git', '-C', str(root), 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], capture_output=True, check=True)
    paths = sorted(set(item.decode('utf-8') for item in process.stdout.split(b'\0') if item))
    problems = []
    for name in paths:
        relative = PurePosixPath(name); file = root / name
        if not file.exists() and not file.is_symlink():
            continue  # tracked deletion is not part of the checkout snapshot
        if file.is_symlink() or FORBIDDEN_DIRECTORIES.intersection(relative.parts) or file.name in {'local.properties', 'keystore.properties', 'signing.properties'} or file.suffix.lower() in FORBIDDEN_SUFFIXES or file.name.startswith('.env'):
            problems.append({'file': name, 'reason': 'private-or-generated-artifact'}); continue
        if file.stat().st_size > 20 * 1024 * 1024:
            problems.append({'file': name, 'reason': 'oversized-public-source-file'}); continue
        data = file.read_bytes()
        if PRIVATE_KEY.search(data):
            problems.append({'file': name, 'reason': 'private-key-material'})
        if b'\0' in data or file.suffix.lower() in {'.png', '.jar'}:
            continue
        text = data.decode('utf-8', errors='replace')
        for match in SENSITIVE_ASSIGNMENT.finditer(text):
            value = match[1].strip().strip('"\x27')
            if value and not any(marker in value.lower() for marker in ('redacted', '<', '${', '$env:', 'os.environ', 'secret', 'synthetic', 'example', 'getenv')):
                problems.append({'file': name, 'reason': 'sensitive-assignment'}); break
        for match in SIGNED_QUERY.finditer(text):
            if not SYNTHETIC.fullmatch(match[1]) and not any(marker in match[1] for marker in ('REDACTED', '<', '${')):
                problems.append({'file': name, 'reason': 'possible-signed-url'}); break
    return {'checkedFiles': len(paths), 'passed': not problems, 'problems': problems,
            'limits': ['No Git history scan', 'No image OCR', 'No credential validity probe', 'Manual review remains required']}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    result = inspect(args.root.resolve())
    print(json.dumps(result, indent=2))
    raise SystemExit(0 if result['passed'] else 1)
