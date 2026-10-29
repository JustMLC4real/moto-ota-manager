"""Verify release metadata, signing and the dynamic root/JNI/Worker DEX contract.

Uses only Python stdlib and the Android SDK's aapt2/apksigner. Never installs an
APK, accesses a phone, loads native code or treats this as OTA safety evidence.
"""
import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
from pathlib import Path
from zipfile import ZipFile

from verify_apk_layout import verify as verify_native_layout

ROOTS = (
    'nl.moto.ota.rootprobe.EngineProbe', 'nl.moto.ota.rootprobe.FirmwareSignatureProbe',
    'nl.moto.ota.rootprobe.StockRestoreProbe', 'nl.moto.ota.rootprobe.importer.MotorolaPackageImportProbe',
    'nl.moto.ota.rootprobe.staging.MotorolaStageProbe',
    'nl.moto.ota.rootprobe.MagiskRuntimeProbe', 'nl.moto.ota.rootprobe.DirectOtaProbe',
    'nl.moto.ota.rootprobe.MagiskInactiveProbe', 'nl.moto.ota.rootprobe.ControlledRebootProbe',
)
WORKERS = ('nl.moto.ota.platform.download.OtaDownloadWorker',
           'nl.moto.ota.platform.monitoring.MotorolaMonitorWorker',
           'nl.moto.ota.platform.update.PostUpdateObserverWorker')
JNI = 'nl.moto.ota.platform.staging.AtomicStagePublisher'
OLD_DEBUG_CERT = '21e80a8850a8f89759b7fba9e6ce693f3fdbe53de921d1a584a3bca130f92865'
ALLOWED_PERMISSIONS = {
    'android.permission.INTERNET', 'android.permission.POST_NOTIFICATIONS',
    'android.permission.FOREGROUND_SERVICE', 'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
    'android.permission.WAKE_LOCK', 'android.permission.ACCESS_NETWORK_STATE',
    'android.permission.RECEIVE_BOOT_COMPLETED', 'nl.moto.ota.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def descriptor(name):
    return 'L' + name.replace('.', '/') + ';'


def dex_definitions(data):
    require(len(data) >= 112 and data[:4] == b'dex\n' and data[7] == 0, 'Unsupported DEX')
    require(struct.unpack_from('<I', data, 40)[0] == 0x12345678, 'Unsupported DEX endianness')

    def table(offset, width):
        count, start = struct.unpack_from('<II', data, offset)
        require(count <= 2_000_000 and start + count * width <= len(data), 'DEX table bounds')
        return count, start

    def uleb(offset):
        value = 0
        for index in range(5):
            require(offset < len(data), 'Truncated DEX integer')
            byte = data[offset]; offset += 1; value |= (byte & 127) << (index * 7)
            if byte < 128:
                return value, offset
        raise ValueError('Invalid DEX integer')

    count, start = table(56, 4)
    strings = []
    for index in range(count):
        pointer = struct.unpack_from('<I', data, start + index * 4)[0]
        _, pointer = uleb(pointer)
        end = data.find(b'\0', pointer)
        require(end >= pointer, 'Unterminated DEX string')
        # Dynamic class/method descriptors are ASCII. Other MUTF-8 strings need
        # not be interpreted to establish this contract.
        strings.append(data[pointer:end].decode('utf-8', errors='replace'))
    count, start = table(64, 4)
    types = [strings[struct.unpack_from('<I', data, start + index * 4)[0]] for index in range(count)]
    count, start = table(72, 12)
    protos = []
    for index in range(count):
        _, result, parameters = struct.unpack_from('<III', data, start + index * 12)
        arguments = []
        if parameters:
            require(parameters + 4 <= len(data), 'DEX proto bounds')
            size = struct.unpack_from('<I', data, parameters)[0]
            require(size <= 256 and parameters + 4 + size * 2 <= len(data), 'DEX parameters bounds')
            arguments = [types[struct.unpack_from('<H', data, parameters + 4 + i * 2)[0]] for i in range(size)]
        protos.append('(' + ''.join(arguments) + ')' + types[result])
    count, start = table(88, 8)
    methods = [struct.unpack_from('<HHI', data, start + index * 8) for index in range(count)]
    count, start = table(96, 32)
    definitions = {}
    for index in range(count):
        class_index, _, _, _, _, _, class_data, _ = struct.unpack_from('<8I', data, start + index * 32)
        name = types[class_index]; entries = {}
        if class_data:
            field_static, cursor = uleb(class_data)
            field_instance, cursor = uleb(cursor)
            direct, cursor = uleb(cursor); virtual, cursor = uleb(cursor)
            require(field_static + field_instance + direct + virtual <= 1_000_000, 'DEX member bounds')
            for _ in range(field_static + field_instance):
                _, cursor = uleb(cursor); _, cursor = uleb(cursor)
            for size in (direct, virtual):
                method_index = 0
                for _ in range(size):
                    difference, cursor = uleb(cursor); method_index += difference
                    access, cursor = uleb(cursor); code, cursor = uleb(cursor)
                    owner, proto, method_name = methods[method_index]
                    require(types[owner] == name, 'DEX method owner mismatch')
                    entries[(strings[method_name], protos[proto])] = (access, code)
        definitions[name] = entries
    return definitions


def verify_dynamic_contract(archive):
    definitions = {}
    for name in archive.namelist():
        if re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', name):
            definitions.update(dex_definitions(archive.read(name)))

    def member(owner, name, proto, flags, code_required):
        members = definitions.get(descriptor(owner))
        require(members is not None, 'Missing kept class: ' + owner)
        actual = members.get((name, proto))
        require(actual is not None and actual[0] & flags == flags and bool(actual[1]) == code_required,
                'Missing/changed dynamic method: ' + owner + '.' + name + proto)

    for root in ROOTS:
        member(root, 'main', '([Ljava/lang/String;)V', 0x9, True)
    for worker in WORKERS:
        member(worker, '<init>', '(Landroid/content/Context;Landroidx/work/WorkerParameters;)V', 0x1, True)
    member(JNI, 'publishNative', '(Ljava/lang/String;Ljava/lang/String;J)I', 0x108, False)
    member(JNI, 'sandboxPublishNative', '(Ljava/lang/String;J)I', 0x108, False)
    for name in ('android.os.IUpdateEngine', 'android.os.IUpdateEngine$Stub',
                 'android.os.IUpdateEngineCallback', 'android.os.IUpdateEngineCallback$Stub'):
        require(descriptor(name) in definitions, 'Missing kept Binder interface: ' + name)
    return {'rootEntrypoints': list(ROOTS), 'workers': list(WORKERS), 'jni': JNI}


def run(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    return result.returncode, result.stdout + result.stderr


def verify(args):
    apk = args.apk.resolve()
    require(apk.is_file() and apk.stat().st_size <= 512 * 1024 * 1024, 'APK missing or oversized')
    tools = args.build_tools.resolve()
    aapt = tools / ('aapt2.exe' if os.name == 'nt' else 'aapt2')
    code, badging = run([str(aapt), 'dump', 'badging', str(apk)])
    require(code == 0, 'aapt2 could not inspect APK')
    require('application-debuggable' not in badging, 'Release APK is debuggable')
    for expression, expected in ((r"name='([^']+)'", 'nl.moto.ota'), (r"versionName='([^']+)'", args.version),
                                 (r"versionCode='([^']+)'", str(args.version_code)),
                                 (r"^(?:minSdkVersion|sdkVersion):'([^']+)'", '33'), (r"^targetSdkVersion:'([^']+)'", '35')):
        match = re.search(expression, badging, re.MULTILINE)
        require(match and match[1] == expected, 'Unexpected package/version/SDK metadata')
    permissions = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", badging))
    require(permissions <= ALLOWED_PERMISSIONS, 'Unexpected release permission: ' + ','.join(sorted(permissions - ALLOWED_PERMISSIONS)))
    code, xml = run([str(aapt), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'])
    require(code == 0, 'aapt2 could not inspect manifest')
    for attribute, expected in (('allowBackup', '0x0'), ('usesCleartextTraffic', '0x0'), ('extractNativeLibs', '0xffffffff')):
        matches = re.findall(r'(?:android:|http://schemas.android.com/apk/res/android:)' + attribute +
                             r'\([^)]*\)=(?:\(type 0x12\))?(0x[0-9a-f]+|true|false)(?=\s|$)', xml)
        normalized = {'false': '0x0', 'true': '0xffffffff'}
        require(len(matches) == 1 and normalized.get(matches[0], matches[0]) == expected,
                'Unexpected manifest setting: ' + attribute)
    with ZipFile(apk) as archive:
        require({'assets/licenses/NOTICE.txt', 'assets/licenses/apache-2.0.txt', 'assets/licenses/skia-bsd.txt'} <= set(archive.namelist()), 'Missing bundled upstream license notices')
        contract = verify_dynamic_contract(archive)
        unsigned_markers = [name for name in archive.namelist() if re.fullmatch(r'META-INF/[^/]+\.(?:RSA|DSA|EC|SF)', name, re.I)]
        with apk.open('rb') as input_file:
            input_file.seek(max(0, archive.start_dir - 16)); signing_magic = input_file.read(16)
    signer = tools / 'lib' / 'apksigner.jar'
    code, signature = run([args.java, '-jar', str(signer), 'verify', '--verbose', '--print-certs', str(apk)])
    certificate = None
    if args.unsigned:
        require(code != 0 and not unsigned_markers and signing_magic != b'APK Sig Block 42', 'Expected genuinely unsigned CI validation build')
    else:
        expected = args.expected_cert_sha256.replace(':', '').lower()
        require(re.fullmatch(r'[a-f0-9]{64}', expected) is not None, 'Expected release certificate SHA-256 is required')
        require(code == 0 and re.search(r'Verified using v2 scheme[^\n]*: true', signature), 'Release signature verification failed')
        certificates = re.findall(r'Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})', signature)
        require(len(certificates) == 1 and certificates[0].lower() == expected, 'Release signer does not match independently pinned certificate')
        certificate = certificates[0].lower()
        require(certificate != OLD_DEBUG_CERT and not re.search(r'certificate DN:.*CN=Android Debug', signature, re.I), 'Debug key is not a release key')
    return {'verified': True, 'kind': 'unsigned-ci-validation' if args.unsigned else 'signed-release',
            'version': args.version, 'versionCode': args.version_code, 'debuggable': False,
            'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'signerCertificateSha256': certificate,
            'permissions': sorted(permissions), 'dynamicContract': contract,
            'nativeLibraries': verify_native_layout(apk)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--build-tools', type=Path, required=True)
    parser.add_argument('--version', default='1.0.0')
    parser.add_argument('--version-code', type=int, default=4)
    parser.add_argument('--java', default='java')
    parser.add_argument('--unsigned', action='store_true')
    parser.add_argument('--expected-cert-sha256', default=os.environ.get('MOTO_RELEASE_CERT_SHA256', ''))
    options = parser.parse_args()
    print(json.dumps(verify(options), indent=2))
