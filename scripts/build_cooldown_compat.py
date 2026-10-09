#!/usr/bin/env python3
"""Build two order-independent packs/JARs from the user's local Cooldown 1.3 pack."""
import argparse
import copy
import hashlib
import io
import json
import re
import zipfile
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parents[1]
PLAYER = "entity/player.entity.json"
BUNDLED_GLOW = "GeyserJavaGlowing.mcpack"
BUNDLED_COOLDOWN = "geyser-cooldown-animation.mcpack"
LEGACY_COOLDOWNS = {"cooldown_sword", "cooldown_axe", "cooldown_mace"}


def parse(data):
    # The supplied Bedrock files contain whole-line JSON comments.
    return json.loads(re.sub(r"(?m)^\s*//.*$", "", data.decode("utf-8-sig")))


def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def read_archive(data):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = [entry for entry in archive.infolist() if not entry.is_dir()]
        if sum(entry.file_size for entry in entries) > 64 * 1024 * 1024:
            raise ValueError("Resource pack exceeds 64 MiB")
        result = {}
        for entry in entries:
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or ".." in path.parts or "\\" in entry.filename:
                raise ValueError("Unsafe archive path: " + entry.filename)
            if entry.filename in result:
                raise ValueError("Duplicate archive path: " + entry.filename)
            result[entry.filename] = archive.read(entry)
        return result


def archive_bytes(files):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o644 << 16
            archive.writestr(entry, data)
    return output.getvalue()


def load_cooldown(path):
    data = path.read_bytes()
    if path.suffix.lower() == ".jar":
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            data = archive.read(BUNDLED_COOLDOWN)
    return read_archive(data)


def merge_mapping(glowing, cooldown, field):
    merged = copy.deepcopy(glowing.get(field, {}))
    for name, value in cooldown.get(field, {}).items():
        if name in merged and merged[name] != value:
            # Outline cape material must survive the vanilla cape declaration.
            if field == "materials" and name == "cape":
                continue
            raise ValueError(f"Unsupported {field} conflict: {name}")
        merged[name] = copy.deepcopy(value)
    return merged


def merge_player(glow_data, cooldown_data):
    result = copy.deepcopy(parse(glow_data))
    glow = result["minecraft:client_entity"]["description"]
    cooldown = parse(cooldown_data)["minecraft:client_entity"]["description"]
    if glow["identifier"] != "minecraft:player" or cooldown["identifier"] != "minecraft:player":
        raise ValueError("Expected minecraft:player definitions")
    if cooldown["animations"].get("gca_recovery") != "controller.animation.geyser_cooldown.recovery":
        raise ValueError("Expected the server-timed Cooldown 1.3 recovery controller")
    for field in ("materials", "textures", "geometry", "animations"):
        glow[field] = merge_mapping(glow, cooldown, field)
    scripts = glow["scripts"]
    cooldown_scripts = cooldown["scripts"]
    scripts["variables"] = merge_mapping(scripts, cooldown_scripts, "variables")
    for phase in ("initialize", "pre_animation"):
        for statement in cooldown_scripts.get(phase, []):
            # Keep Glowing's vanilla setup, slim/wide detection and outline variables.
            if re.search(r"\b(?:variable|v)\.gca_", statement) and statement not in scripts[phase]:
                scripts[phase].append(statement)
    # Server-timed recovery replaces Blurry's separate fixed-time cooldown.
    # Keep the original aliases so other optional RP integrations can still resolve them.
    scripts["animate"] = [
        entry for entry in scripts["animate"]
        if not (isinstance(entry, str) and entry in LEGACY_COOLDOWNS)
    ]
    for entry in cooldown_scripts["animate"]:
        if entry not in scripts["animate"]:
            scripts["animate"].append(copy.deepcopy(entry))
    return encode(result)


def compatibility_files(glowing, cooldown):
    merged = dict(glowing)
    for name, data in cooldown.items():
        if name in {"manifest.json", PLAYER, "pack_icon.png"}:
            continue
        if name in merged and merged[name] != data:
            raise ValueError("Unsupported resource path conflict: " + name)
        merged[name] = data
    merged[PLAYER] = merge_player(glowing[PLAYER], cooldown[PLAYER])
    # Preserve all source notices; the copied third-party assets do not become MIT.
    merged["compatibility/CREDITS.txt"] = (
        "Glowing Player Outline: BigGamers4u; Geyser bridge: GeyserJavaGlowing.\n"
        "Cooldown assets: user's local Geyser Cooldown Crossplay Weapons 1.3.\n"
        "See THIRD_PARTY_NOTICES.md, LICENSE and licenses/ for original terms.\n"
        "Compatibility assembly does not change the source assets' licenses.\n"
    ).encode()
    return merged


def with_manifest(files, manifest_data, label):
    result = dict(files)
    manifest = parse(manifest_data)
    manifest["header"]["name"] += " + " + label
    manifest["header"]["description"] += " | Glowing/Cooldown compatibility"
    # Keep each pack's identity, but invalidate the cached older player definition.
    version = list(manifest["header"]["version"])
    if len(version) != 3 or version[2] >= 65535:
        raise ValueError("Cannot increment resource-pack patch version")
    version[2] += 1
    manifest["header"]["version"] = version
    for module in manifest["modules"]:
        module["version"] = version
    result["manifest.json"] = encode(manifest)
    return result


def patch_jar(source, output, resource_name, pack):
    with zipfile.ZipFile(source) as original:
        if resource_name not in original.namelist():
            raise ValueError("Bundled resource pack missing in " + str(source))
        if any(re.match(r"META-INF/.*\.(SF|RSA|DSA|EC)$", name, re.I)
               for name in original.namelist()):
            raise ValueError("Refusing to invalidate a signed JAR")
        with zipfile.ZipFile(output, "w") as patched:
            for entry in original.infolist():
                data = pack if entry.filename == resource_name else original.read(entry)
                patched.writestr(entry, data)


def build(cooldown_input, glowing_jar, output):
    glowing = {p.relative_to(HERE / "Glowing Player Outline RP").as_posix(): p.read_bytes()
               for p in (HERE / "Glowing Player Outline RP").rglob("*") if p.is_file()}
    cooldown = load_cooldown(cooldown_input)
    merged = compatibility_files(glowing, cooldown)
    glow_pack = archive_bytes(with_manifest(merged, glowing["manifest.json"], "Cooldown compat"))
    cooldown_pack = archive_bytes(with_manifest(merged, cooldown["manifest.json"], "Glowing compat"))
    output.mkdir(parents=True, exist_ok=True)
    assets = {
        "GeyserJavaGlowing-cooldown-compat.mcpack": glow_pack,
        "GeyserCooldownAnimation-glowing-compat.mcpack": cooldown_pack,
    }
    for name, data in assets.items():
        (output / name).write_bytes(data)
    if glowing_jar is not None:
        patch_jar(glowing_jar, output / (glowing_jar.stem + "-cooldown-compat.jar"),
                  BUNDLED_GLOW, glow_pack)
    if cooldown_input.suffix.lower() == ".jar":
        patch_jar(cooldown_input, output / (cooldown_input.stem + "-glowing-compat.jar"),
                  BUNDLED_COOLDOWN, cooldown_pack)
    report = {
        "glowing_source": str(HERE / "Glowing Player Outline RP"),
        "cooldown_input": str(cooldown_input.resolve()),
        "cooldown_sha256": hashlib.sha256(cooldown_input.read_bytes()).hexdigest(),
        "shared_player_sha256": hashlib.sha256(merged[PLAYER]).hexdigest(),
        "assets": {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                   for p in sorted(output.iterdir()) if p.suffix in {".jar", ".mcpack"}},
    }
    (output / "compatibility-report.json").write_bytes(encode(report))
    (output / "SHA256SUMS.txt").write_text(
        "".join(f"{digest}  {name}\n" for name, digest in report["assets"].items()), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cooldown-input", type=Path, required=True,
                        help="Local Cooldown 1.3 extension JAR or its mcpack")
    parser.add_argument("--glowing-jar", type=Path, help="Built Glowing extension JAR")
    parser.add_argument("--output-dir", type=Path, default=HERE / "build/cooldown-compat")
    args = parser.parse_args()
    sources = [args.cooldown_input, args.glowing_jar]
    if any(source is not None and args.output_dir.resolve() in source.resolve().parents
           for source in sources):
        parser.error("Keep input archives outside the output directory")
    build(args.cooldown_input, args.glowing_jar, args.output_dir)


if __name__ == "__main__":
    main()
