#!/usr/bin/env python3
"""Exercise official Magisk tools on synthetic files in a disposable emulator.

Supply your own unmodified official v31.1 APK. No Magisk code/binaries are bundled.
This deliberately never opens a device partition, installs an OTA or reboots.
Only an explicit emulator serial with ro.kernel.qemu=1 is accepted.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import subprocess
import tempfile
import uuid
import zipfile
from pathlib import Path

APK_SHA256 = "89cefac0a318afa39fb3880c45c87b036c05bc1ebf02a7a715b7f8d7c26ac6e7"
APK_BYTES = 10_679_283
FSTAB = b"/dev/block/test /data f2fs nosuid,nodev wait,check,formattable,fileencryption=aes-256-xts\n"
WRAPPER = '''export BOOTMODE=true
. ./util_functions.sh
export TMPDIR="$PWD/tmp" MAGISKBIN="$PWD" SOURCEDMODE=true
. ./boot_patch.sh ./target-stock.img
'''


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def cpio_entry(name: str, data: bytes = b"", mode: int = 0o100644, ino: int = 1) -> bytes:
    encoded = name.encode() + b"\0"
    numbers = [ino, mode, 0, 0, 1, 0, len(data), 0, 0, 0, 0, len(encoded), 0]
    result = b"070701" + b"".join(f"{value:08x}".encode() for value in numbers) + encoded
    result += b"\0" * (-len(result) % 4)
    result += data
    return result + b"\0" * (-len(result) % 4)


def fixtures(directory: Path) -> list[dict]:
    cpio = (cpio_entry("init", b"#!/system/bin/sh\n# Synthetic file fixture; never booted.\n", 0o100750)
            + cpio_entry("fstab.test", FSTAB, ino=2) + cpio_entry("TRAILER!!!", mode=0, ino=0))
    result = []
    for kind, version, size, kernel in [("init_boot", 4, 8_388_608, b""),
                                        ("boot", 3, 33_554_432, b"SYNTHETIC-KERNEL-NOT-BOOTABLE\n" + bytes(4067))]:
        header = bytearray(4096)
        header[:8] = b"ANDROID!"
        struct.pack_into("<IIII", header, 8, len(kernel), len(cpio), 0, 1584 if version == 4 else 1580)
        struct.pack_into("<I", header, 40, version)
        image = bytes(header) + kernel + bytes(-len(kernel) % 4096) + cpio + bytes(-len(cpio) % 4096)
        image += bytes(size - len(image))
        (directory / f"{kind}.img").write_bytes(image)
        result.append({"partitionType": kind, "bytes": size, "sha256": hashlib.sha256(image).hexdigest(),
                       "sha1": hashlib.sha1(image).hexdigest()})
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True, help="Official unmodified Magisk-v31.1.apk")
    parser.add_argument("--adb", required=True, help="Explicit path to Android SDK adb")
    parser.add_argument("--serial", required=True, help="Explicit disposable emulator-NNNN serial")
    parser.add_argument("--output", type=Path, required=True, help="New result JSON path")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]{4,5}", args.serial):
        parser.error("Only an explicit emulator serial is supported")
    if args.apk.stat().st_size != APK_BYTES or sha256(args.apk) != APK_SHA256:
        parser.error("APK is not the pinned official Magisk 31.1 release")
    if args.output.exists():
        parser.error("Use a new output path; previous evidence is never overwritten")

    def adb(*arguments: str) -> str:
        result = subprocess.run([args.adb, "-s", args.serial, *arguments], check=True, capture_output=True, text=True)
        return result.stdout

    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        parser.error("Target did not prove ro.kernel.qemu=1; no files were pushed")
    if adb("shell", "getprop", "ro.product.cpu.abi").strip() != "x86_64":
        parser.error("This reproducible tool test requires the x86_64 emulator")
    remote = "/data/local/tmp/moto-ota-file-proof-" + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix="moto-magisk-file-proof-") as name:
        work = Path(name)
        tools = {"boot_patch.sh": "assets/boot_patch.sh", "util_functions.sh": "assets/util_functions.sh"}
        tools.update({item: f"lib/x86_64/lib{item}.so" for item in ("magiskboot", "magiskinit", "magisk", "init-ld", "busybox")})
        with zipfile.ZipFile(args.apk) as package:
            for filename, entry in tools.items():
                (work / filename).write_bytes(package.read(entry))
        cases = fixtures(work)
        (work / "patch-wrapper.sh").write_text(WRAPPER, newline="\n")
        commands = f'''#!/system/bin/sh
set -eu
[ "$(/system/bin/getprop ro.kernel.qemu)" = 1 ] || exit 90
cd {remote}
for kind in init_boot boot; do
 mkdir "$kind" "$kind/tmp" "$kind/verify"
 cp boot_patch.sh util_functions.sh magiskboot magiskinit magisk init-ld busybox patch-wrapper.sh "$kind/"
 cp "$kind.img" "$kind/target-stock.img"
 cd "$kind"
 chmod 700 busybox magiskboot magiskinit magisk init-ld
 /system/bin/env -i PATH=/system/bin BOOTMODE=true KEEPVERITY=true KEEPFORCEENCRYPT=true RECOVERYMODE=false VENDORBOOT=false LEGACYSAR=false PATCHVBMETAFLAG=false PREINITDEVICE=metadata ASH_STANDALONE=1 ./busybox ash ./patch-wrapper.sh
 cd verify
 ../magiskboot unpack ../new-boot.img
 set +e
 ../magiskboot cpio ramdisk.cpio test
 status=$?
 set -e
 [ "$status" = 1 ] || exit 91
 ../magiskboot cpio ramdisk.cpio 'exists overlay.d/sbin/magisk.xz'
 ../magiskboot cpio ramdisk.cpio 'extract .backup/.magisk embedded.config'
 ../magiskboot cpio ramdisk.cpio 'extract fstab.test fstab.actual'
 chmod 600 embedded.config
 cd ../..
done
'''
        (work / "run.sh").write_text(commands, newline="\n")
        adb("shell", "mkdir", remote)
        adb("push", str(work) + "/.", remote + "/")
        log = adb("shell", "sh", remote + "/run.sh")
        for case in cases:
            kind = case["partitionType"]
            for remote_name, local_name in [("target-stock.img", "original"), ("new-boot.img", "patched"),
                                             ("verify/embedded.config", "config"), ("verify/fstab.actual", "fstab")]:
                adb("pull", f"{remote}/{kind}/{remote_name}", str(work / f"{kind}-{local_name}"))
            original = work / f"{kind}-original"
            patched = (work / f"{kind}-patched").read_bytes()
            config = dict(line.split("=", 1) for line in (work / f"{kind}-config").read_text().splitlines() if line)
            assert original.stat().st_size == case["bytes"] and sha256(original) == case["sha256"]
            assert config["SHA1"] == case["sha1"]
            assert config["KEEPVERITY"] == "true" and config["KEEPFORCEENCRYPT"] == "true"
            assert config["RECOVERYMODE"] == "false" and config["VENDORBOOT"] == "false"
            assert (work / f"{kind}-fstab").read_bytes() == FSTAB
            assert 4096 <= len(patched) <= case["bytes"] and patched[:8] == b"ANDROID!"
            padded = patched + bytes(case["bytes"] - len(patched))
            case.update({"patchedImageBytes": len(patched), "paddedImageSha256": hashlib.sha256(padded).hexdigest(),
                         "embeddedStockSha1": config["SHA1"], "nativeCpioPatchedTest": 1,
                         "sourceUnchanged": True, "fstabUnchangedWithKeepFlags": True, "passed": True})
        report = {"schema": 1, "toolApkSha256": APK_SHA256, "qemuVerified": True,
                  "scope": "Actual official tools on synthetic regular files. No partition, OTA, root daemon or reboot operation.",
                  "cases": cases}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        args.output.with_suffix(".log").write_text(log)
        print(f"PASS: {len(cases)} genuine Magisk file-tool cases; evidence saved to {args.output.name}")


if __name__ == "__main__":
    main()
