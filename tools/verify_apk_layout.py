"""Check packaged ABI and 16 KiB ELF alignment without importing third-party code.

Development check only; this does not establish OTA safety or a physical-device run.
Reference: https://developer.android.com/guide/practices/page-sizes
"""
import json
import struct
import sys
import zipfile
from pathlib import Path


def verify(apk: Path) -> list[dict]:
    records = []
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'Duplicate APK entries'
        assert 'AndroidManifest.xml' in names and 'classes.dex' in names
        for entry in archive.infolist():
            if not entry.filename.endswith('.so'):
                continue
            parts = entry.filename.split('/')
            assert len(parts) == 3 and parts[0] == 'lib'
            assert parts[1] in ('arm64-v8a', 'x86_64')
            assert 64 <= entry.file_size <= 64 * 1024 * 1024
            data = archive.read(entry)
            assert data[:6] == b'\x7fELF\x02\x01', 'Expected 64-bit little-endian ELF'
            machine = struct.unpack_from('<H', data, 18)[0]
            assert machine == {'arm64-v8a': 183, 'x86_64': 62}[parts[1]]
            phoff = struct.unpack_from('<Q', data, 32)[0]
            size, count = struct.unpack_from('<HH', data, 54)
            assert size == 56 and 0 < count <= 256 and 64 <= phoff
            assert phoff + size * count <= len(data)
            loads, relro = [], []
            for index in range(count):
                kind, flags, offset, address, _, file_size, memory_size, align = struct.unpack_from('<IIQQQQQQ', data, phoff + size * index)
                if kind == 1:
                    assert align >= 16384 and align & (align - 1) == 0, entry.filename
                    assert offset % 16384 == address % 16384, entry.filename
                    assert offset + file_size <= len(data) and memory_size >= file_size
                    loads.append(align)
                if kind == 0x6474E552:
                    relro.append((address + memory_size) % 16384)
            assert loads
            if parts[2] == 'libmoto_stage_publish.so':
                assert relro and all(remainder == 0 for remainder in relro), 'Own JNI RELRO is not 16 KiB aligned'
            records.append({'file': entry.filename, 'loadAlignments': loads,
                            'relroEndModulo16384': relro})
    assert {item['file'].split('/')[1] for item in records} == {'arm64-v8a', 'x86_64'}
    assert {item['file'].split('/')[1] for item in records if item['file'].endswith('/libmoto_stage_publish.so')} == {'arm64-v8a', 'x86_64'}
    return records


if __name__ == '__main__':
    print(json.dumps({'verified': True, 'libraries': verify(Path(sys.argv[1]))}, indent=2))
