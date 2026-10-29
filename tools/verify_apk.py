#!/usr/bin/env python3
"""Check the shipped API101 descriptor and real DEX class definitions."""

import argparse
import struct
import subprocess
import zipfile
from pathlib import Path
from xml.etree import ElementTree


ENTRY = "io.github.chsbuffer.miuihelper.ModernXposedInit"
PREFIX = "META-INF/xposed/"


def dex_classes(data):
    if not data.startswith(b"dex\n"):
        raise ValueError("Not a DEX file")
    string_count, string_offset = struct.unpack_from("<II", data, 56)
    type_count, type_offset = struct.unpack_from("<II", data, 64)
    class_count, class_offset = struct.unpack_from("<II", data, 96)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from("<I", data, string_offset + index * 4)[0]
        while data[offset] & 0x80:
            offset += 1
        offset += 1  # Skip the UTF-16 size encoded as ULEB128.
        strings.append(data[offset:data.index(0, offset)].decode("utf-8", errors="replace"))
    types = [strings[struct.unpack_from("<I", data, type_offset + index * 4)[0]]
             for index in range(type_count)]
    return {types[struct.unpack_from("<I", data, class_offset + index * 32)[0]]
            for index in range(class_count)}


def verify(apk, aapt2):
    repository = Path(__file__).resolve().parents[1]
    scope_resource = repository / "app/src/main/res/values/arrays.xml"
    expected_scopes = [item.text for item in ElementTree.parse(scope_resource)
                       .find("string-array[@name='scope']")]
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        entries = archive.read(PREFIX + "java_init.list").decode().splitlines()
        assert entries == [ENTRY], entries
        properties = dict(line.split("=", 1) for line in
                          archive.read(PREFIX + "module.prop").decode().splitlines()
                          if line.strip() and not line.startswith("#"))
        assert properties["minApiVersion"] == "101", properties
        assert properties["targetApiVersion"] == "101", properties
        scopes = archive.read(PREFIX + "scope.list").decode().splitlines()
        assert scopes == expected_scopes and len(scopes) == 7, scopes
        assert "assets/xposed_init" not in names, "Legacy entry still packaged"
        classes = set()
        for name in names:
            if name.startswith("classes") and name.endswith(".dex") and "/" not in name:
                classes.update(dex_classes(archive.read(name)))
        assert "L" + ENTRY.replace(".", "/") + ";" in classes, "R8 removed the entry class"
        assert "Lio/github/chsbuffer/miuihelper/QolApplication;" in classes
        assert "Lio/github/libxposed/service/XposedProvider;" in classes
        assert not any(name.startswith("Lio/github/libxposed/api/") for name in classes), \
            "Framework API classes must remain compileOnly"
        assert not any(name.startswith("Lde/robv/android/xposed/") for name in classes), \
            "Legacy framework classes must remain compileOnly"
    manifest = subprocess.check_output(
        [str(aapt2), "dump", "xmltree", str(apk), "--file", "AndroidManifest.xml"], text=True)
    assert "io.github.chsbuffer.miuihelper.QolApplication" in manifest
    assert "io.github.libxposed.service.XposedProvider" in manifest
    for obsolete in ("xposedmodule", "xposedminversion", "xposedsharedprefs", "xposedscope"):
        assert obsolete not in manifest, f"Legacy metadata remains: {obsolete}"
    print(f"PASS {apk}: API101 descriptor, 7 scopes, entry DEX class, provider, compileOnly APIs")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--aapt2", type=Path, required=True)
    args = parser.parse_args()
    verify(args.apk, args.aapt2)
