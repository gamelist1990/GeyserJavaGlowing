"""Verify the actual local Cooldown/Glowing compatibility pair when paths are provided."""
import hashlib
import importlib.util
import io
import json
import os
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("cooldown_compat", HERE / "scripts/build_cooldown_compat.py")
compat = importlib.util.module_from_spec(spec)
spec.loader.exec_module(compat)


class MergeTests(unittest.TestCase):
    def setUp(self):
        self.glow = (HERE / "Glowing Player Outline RP/entity/player.entity.json").read_bytes()
        self.original = compat.parse(self.glow)["minecraft:client_entity"]["description"]
        self.cooldown = {
            "minecraft:client_entity": {"description": {
                "identifier": "minecraft:player",
                "materials": {"cape": "entity_alphatest"},
                "animations": {"gca_recovery": "controller.animation.geyser_cooldown.recovery"},
                "scripts": {
                    "initialize": ["variable.gca_requested = 0;"],
                    "pre_animation": ["variable.gca_hand = !query.is_item_equipped('main_hand');"],
                    "animate": ["root", "gca_recovery"],
                    "variables": {"variable.attack_time": "public"},
                },
            }}
        }

    def merged(self):
        return compat.parse(compat.merge_player(self.glow, compat.encode(self.cooldown)))[
            "minecraft:client_entity"]["description"]

    def test_outline_and_existing_integrations_survive(self):
        merged = self.merged()
        for field in ("materials", "textures", "geometry", "render_controllers"):
            self.assertEqual(merged[field], self.original[field])
        for alias, definition in self.original["animations"].items():
            self.assertEqual(merged["animations"][alias], definition)
        self.assertTrue(merged["enable_attachables"])
        self.assertIn("variable.wide = !variable.slim;", merged["scripts"]["pre_animation"])

    def test_only_one_cooldown_driver_runs(self):
        merged = self.merged()
        animate = merged["scripts"]["animate"]
        self.assertEqual(animate.count("root"), 1)
        self.assertEqual(animate.count("gca_recovery"), 1)
        self.assertTrue(compat.LEGACY_COOLDOWNS.isdisjoint(animate_entry for animate_entry in animate
                                                       if isinstance(animate_entry, str)))
        self.assertIn("variable.gca_requested = 0;", merged["scripts"]["initialize"])
        self.assertIn("variable.gca_hand = !query.is_item_equipped('main_hand');",
                      merged["scripts"]["pre_animation"])
        self.assertIn({"elytra_default_controller":
                       "query.is_item_name_any('slot.armor.chest', 0, 'minecraft:elytra')"}, animate)

    def test_unknown_animation_conflict_is_rejected(self):
        description = self.cooldown["minecraft:client_entity"]["description"]
        description["animations"]["root"] = "controller.animation.unrelated.root"
        with self.assertRaisesRegex(ValueError, "Unsupported animations conflict: root"):
            self.merged()

    def test_unsupported_cooldown_is_rejected(self):
        self.cooldown["minecraft:client_entity"]["description"]["animations"].clear()
        with self.assertRaisesRegex(ValueError, "Cooldown 1.3"):
            self.merged()


@unittest.skipUnless(os.environ.get("COOLDOWN_COMPAT_INPUT"),
                     "Set COOLDOWN_COMPAT_INPUT to validate the locally generated archive pair")
class ArchiveTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.input = Path(os.environ["COOLDOWN_COMPAT_INPUT"])
        cls.output = Path(os.environ.get("COOLDOWN_COMPAT_OUTPUT", HERE / "build/cooldown-compat"))
        cls.cooldown = compat.load_cooldown(cls.input)
        cls.glow = {p.relative_to(HERE / "Glowing Player Outline RP").as_posix(): p.read_bytes()
                    for p in (HERE / "Glowing Player Outline RP").rglob("*") if p.is_file()}
        cls.packs = [
            compat.read_archive((cls.output / "GeyserJavaGlowing-cooldown-compat.mcpack").read_bytes()),
            compat.read_archive((cls.output / "GeyserCooldownAnimation-glowing-compat.mcpack").read_bytes()),
        ]

    def test_both_priority_orders_keep_glowing_and_server_timing(self):
        original_glow = compat.parse(self.glow[compat.PLAYER])["minecraft:client_entity"]["description"]
        original_cooldown = compat.parse(self.cooldown[compat.PLAYER])["minecraft:client_entity"]["description"]
        self.assertNotIn("gca_recovery", original_glow["animations"])
        self.assertNotIn("outline", original_cooldown["materials"])
        for stack in (self.packs, list(reversed(self.packs))):
            effective = {}
            for pack in stack:
                effective.update(pack)
            player = compat.parse(effective[compat.PLAYER])["minecraft:client_entity"]["description"]
            self.assertEqual(player["materials"]["outline"], original_glow["materials"]["outline"])
            self.assertEqual(player["render_controllers"], original_glow["render_controllers"])
            self.assertEqual(player["animations"]["gca_recovery"],
                             original_cooldown["animations"]["gca_recovery"])
            self.assertIn("gca_recovery", player["scripts"]["animate"])
            self.assertIn("variable.wide = !variable.slim;", player["scripts"]["pre_animation"])

    def test_shared_resources_are_identical(self):
        one = {k: v for k, v in self.packs[0].items() if k != "manifest.json"}
        two = {k: v for k, v in self.packs[1].items() if k != "manifest.json"}
        self.assertEqual(one, two)
        for pack in self.packs:
            for name, data in pack.items():
                self.assertLess(len(name), 80, name)
                if name.endswith((".json", ".material")):
                    compat.parse(data)

    def test_500_timing_notifications_and_weapons_are_preserved(self):
        for name, data in self.cooldown.items():
            if name.startswith(("animations/", "animation_controllers/", "attachables/", "models/",
                                "render_controllers/", "licenses/")) or name in {
                                    "LICENSE", "THIRD_PARTY_NOTICES.md"}:
                self.assertEqual(self.packs[0][name], data, name)
        timing = compat.parse(self.packs[0]["animations/cooldown.animation.json"])["animations"]
        expected = {"animation.player.cooldown_20ms_" + str(n) for n in range(1, 501)}
        self.assertTrue(expected.issubset(timing))
        player = compat.parse(self.packs[0][compat.PLAYER])["minecraft:client_entity"]["description"]
        # Every alias referenced by Cooldown's player controller must be available.
        controllers = compat.parse(self.packs[0]["animation_controllers/player.animation_controllers.json"])
        for controller in controllers["animation_controllers"].values():
            for state in controller["states"].values():
                for entry in state.get("animations", []):
                    aliases = [entry] if isinstance(entry, str) else list(entry)
                    for alias in aliases:
                        self.assertIn(alias, player["animations"])

    def test_glowing_geometry_armor_materials_and_elytra_are_preserved(self):
        for name, data in self.glow.items():
            if name not in {"manifest.json", compat.PLAYER}:
                self.assertEqual(self.packs[0][name], data, name)

    def test_pack_identities_remain_distinct_and_cache_versions_increase(self):
        manifests = [compat.parse(pack["manifest.json"]) for pack in self.packs]
        self.assertNotEqual(manifests[0]["header"]["uuid"], manifests[1]["header"]["uuid"])
        for pack, original in zip(manifests, [self.glow, self.cooldown]):
            before = compat.parse(original["manifest.json"])
            self.assertEqual(pack["header"]["uuid"], before["header"]["uuid"])
            self.assertGreater(pack["header"]["version"], before["header"]["version"])
            self.assertEqual(pack["modules"][0]["uuid"], before["modules"][0]["uuid"])

    def test_patched_jars_only_change_bundled_resource_packs(self):
        originals = [(self.input, compat.BUNDLED_COOLDOWN, "-glowing-compat", 1)]
        if os.environ.get("GLOWING_COMPAT_JAR"):
            originals.append((Path(os.environ["GLOWING_COMPAT_JAR"]),
                              compat.BUNDLED_GLOW, "-cooldown-compat", 0))
        for source, resource, suffix, index in originals:
            if source.suffix != ".jar":
                continue
            patched = self.output / (source.stem + suffix + ".jar")
            with zipfile.ZipFile(source) as old, zipfile.ZipFile(patched) as new:
                self.assertEqual(old.namelist(), new.namelist())
                for name in old.namelist():
                    if name != resource:
                        self.assertEqual(old.read(name), new.read(name), name)
                self.assertEqual(compat.read_archive(new.read(resource)), self.packs[index])

    def test_report_hashes_match_outputs(self):
        report = json.loads((self.output / "compatibility-report.json").read_text())
        self.assertEqual(report["cooldown_sha256"], hashlib.sha256(self.input.read_bytes()).hexdigest())
        for name, digest in report["assets"].items():
            self.assertEqual(hashlib.sha256((self.output / name).read_bytes()).hexdigest(), digest)


if __name__ == "__main__":
    unittest.main()
