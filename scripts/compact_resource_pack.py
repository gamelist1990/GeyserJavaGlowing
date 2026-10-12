#!/usr/bin/env python3
"""Create a lossless compact pack preview without modifying source assets.

JSON tokens (including numeric spelling) and PNG pixels/metadata are preserved.
The input manifest identity is retained: this preview is not a new release.
"""
import argparse
from decimal import Decimal
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import zipfile
import zlib


def compact_json(data):
    source = data.decode("utf-8-sig")
    result = []
    quoted = escaped = False
    index = 0
    while index < len(source):
        char = source[index]
        if quoted:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
            result.append(char)
        elif source[index:index + 2] == "//":
            end = source.find("\n", index)
            index = len(source) if end < 0 else end
            continue
        elif char not in " \t\r\n":
            result.append(char)
        index += 1
    compact = "".join(result).encode("utf-8")
    original = re.sub(r"(?m)^\s*//.*$", "", source)
    kwargs = dict(parse_float=Decimal, parse_int=Decimal)
    if json.loads(original, **kwargs) != json.loads(compact, **kwargs):
        raise ValueError("JSON semantic equivalence failed")
    return compact


def png_chunks(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Invalid PNG signature")
    offset = 8
    result = []
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        end = offset + 12 + length
        if end > len(data):
            raise ValueError("Truncated PNG")
        result.append((data[offset + 4:offset + 8],
                       data[offset + 8:offset + 8 + length], data[offset:end]))
        offset = end
    return result


def compact_icon(data):
    from PIL import Image
    original = png_chunks(data)
    if any(kind == b"acTL" for kind, _, _ in original):
        return data  # Do not rewrite animated PNGs.
    with Image.open(io.BytesIO(data)) as image:
        output = io.BytesIO()
        opaque_rgba = image.mode == "RGBA" and image.getchannel("A").getextrema() == (255, 255)
        encoded = image.convert("RGB") if opaque_rgba else image
        encoded.save(output, format="PNG", optimize=True, compress_level=9)
    candidate = png_chunks(output.getvalue())
    # Reuse all original non-IDAT chunks, preserving profiles, gamma and text.
    for kind in ([b"PLTE", b"tRNS"] if opaque_rgba else [b"IHDR", b"PLTE", b"tRNS"]):
        if [payload for k, payload, _ in original if k == kind] != [
                payload for k, payload, _ in candidate if k == kind]:
            return data
    payload = b"".join(p for k, p, _ in candidate if k == b"IDAT")
    idat = (struct.pack(">I", len(payload)) + b"IDAT" + payload
            + struct.pack(">I", zlib.crc32(b"IDAT" + payload) & 0xffffffff))
    parts = [data[:8]]
    written = False
    for kind, _, raw in original:
        if kind == b"IHDR" and opaque_rgba:
            parts.append(next(raw for k, _, raw in candidate if k == b"IHDR"))
            continue
        if kind == b"IDAT":
            if not written:
                parts.append(idat)
                written = True
        else:
            parts.append(raw)
    compact = b"".join(parts)
    with Image.open(io.BytesIO(data)) as before, Image.open(io.BytesIO(compact)) as after:
        if before.size != after.size or before.convert("RGBA").tobytes() != after.convert("RGBA").tobytes():
            raise ValueError("PNG pixel equivalence failed")
    return compact if len(compact) < len(data) else data


def optimize(source, destination):
    if source.resolve() == destination.resolve():
        raise ValueError("Input and output must be different paths")
    files = {}
    records = []
    with zipfile.ZipFile(source) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        if sum(entry.file_size for entry in entries) > 64 * 1024 * 1024:
            raise ValueError("Input pack exceeds 64 MiB")
        for entry in entries:
            if entry.filename in files:
                raise ValueError("Duplicate pack entry")
            data = archive.read(entry)
            compact = data
            if entry.filename.endswith((".json", ".material")):
                compact = compact_json(data)
            elif entry.filename == "pack_icon.png":
                compact = compact_icon(data)
            files[entry.filename] = compact
            records.append(dict(path=entry.filename, before=len(data), after=len(compact)))
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None or set(archive.namelist()) != set(files):
            raise ValueError("Output archive validation failed")
        for name, data in files.items():
            if archive.read(name) != data:
                raise ValueError("Output content verification failed")
    report = dict(source=str(source.resolve()), output=str(destination.resolve()),
                  before=source.stat().st_size, after=destination.stat().st_size,
                  expanded_before=sum(r["before"] for r in records),
                  expanded_after=sum(r["after"] for r in records),
                  sha256=hashlib.sha256(destination.read_bytes()).hexdigest(),
                  verification="Exact Decimal JSON equality, unchanged tokens, identical PNG pixels and metadata, archive round-trip",
                  files=records)
    destination.with_suffix(".report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: v for k, v in report.items() if k != "files"}, indent=2))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    optimize(args.source, args.destination)
