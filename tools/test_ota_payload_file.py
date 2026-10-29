"""Exercise only the normal OTA staging FILE boundary on a root-capable emulator.

Requires installed matching app/test APKs. Does not apply an OTA, touch a block
device, change SELinux policy, reset app data or reboot anything.
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import uuid
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'emulator-[0-9]{4,5}', args.serial):
        raise ValueError('Only an explicitly selected disposable emulator is accepted')

    def adb(*arguments, timeout=60):
        result = subprocess.run([str(args.adb.resolve()), '-s', args.serial, *arguments],
                                capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError('Emulator command failed: ' + arguments[0])
        return result.stdout.decode('utf-8', errors='strict').strip()

    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise ValueError('Selected target is not a confirmed emulator')
    if 'uid=0(root)' not in adb('shell', 'su', '0', 'id'):
        raise ValueError('This file-only proof requires a root-capable emulator')

    def package_path(package):
        text = adb('shell', 'pm', 'path', package)
        if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/._~=+\-]+/base\.apk', text):
            raise ValueError('Expected one installed standalone APK')
        return text.removeprefix('package:')

    classpath = package_path('nl.moto.ota') + ':' + package_path('nl.moto.ota.test')
    source_dir = '/data/local/tmp/moto-ota-staging-proof'
    adb('shell', 'mkdir', '-p', source_dir)
    results = []
    with tempfile.TemporaryDirectory(prefix='moto-ota-file-proof-') as temporary:
        for case in ('roundtrip', 'reject'):
            identity = str(uuid.uuid4())
            local = Path(temporary) / (identity + '.bin')
            local.write_bytes(os.urandom(8192))
            source = source_dir + '/' + identity + '.bin'
            try:
                adb('push', str(local), source)
                adb('shell', 'chmod', '600', source)
                output = adb('shell', 'su', '0', 'env', 'CLASSPATH=' + classpath,
                             '/system/bin/app_process', '/system/bin',
                             'nl.moto.ota.platform.update.OtaPayloadFileProbe', identity, case)
                value = json.loads(output)
                if value.get('passed') is not True or value.get('case') != case:
                    raise ValueError('Actual OTA file proof failed: ' + case)
                expected = hashlib.sha256(local.read_bytes()).hexdigest()
                if adb('shell', 'sha256sum', source).split()[0] != expected:
                    raise ValueError('Source fixture was changed')
                results.append(value)
            finally:
                adb('shell', 'rm', '-f', source)
    report = {'schema': 1, 'serial': args.serial, 'qemu': True,
              'scope': 'temporary regular files only; no OTA/partition/power API', 'results': results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps({'passed': len(results), 'scope': 'emulator-files-only'}))


if __name__ == '__main__':
    main()
