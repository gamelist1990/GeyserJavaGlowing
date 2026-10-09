import json
import re
import unittest
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]
PACK = HERE / 'build/generated/resource-pack/GeyserJavaGlowing.mcpack'

class PackTests(unittest.TestCase):
    def test_zip_has_valid_manifest_and_required_files(self):
        with zipfile.ZipFile(PACK) as archive:
            names = set(archive.namelist())
            self.assertIn('manifest.json', names)
            manifest = json.loads(archive.read('manifest.json'))
            self.assertEqual(manifest['modules'][0]['type'], 'resources')
            self.assertIn('entity/player.entity.json', names)
            self.assertIn('materials/entity.material', names)
            self.assertTrue(any(p.startswith('models/entity/') for p in names))
            self.assertNotIn('entities/player.json', names)  # no behavior pack
            self.assertNotIn('scripts/glow.js', names)
            rp = archive.read('entity/player.entity.json').decode('utf8')
            self.assertIn("q.property('glow:color') == 2", rp)
            self.assertIn('variable.wide = !variable.slim;', rp)
            self.assertNotIn("q.property('glow:is_slim')", rp)

    def test_bridge_does_not_send_animation(self):
        code = (HERE/'src/main/java/org/pexserver/geyserglowing/GlowingPacketBridge.java').read_text()
        self.assertIn('0x40', code)
        self.assertIn('ClientboundSetEntityDataPacket', code)
        self.assertIn('updatePropertiesBatched', code)
        self.assertNotIn('AnimateEntityPacket', code)

    def test_paths_fit_bedrock_limits_and_json_is_valid(self):
        with zipfile.ZipFile(PACK) as archive:
            for name in archive.namelist():
                self.assertLess(len(name), 80, name)
                if name.endswith(('.json', '.material')):
                    content = archive.read(name).decode('utf8')
                    # Bedrock resource packs accept JSON with line comments.
                    json.loads(re.sub(r'(?m)^\s*//.*$', '', content))

    def test_pack_matches_source_files(self):
        root = HERE / 'Glowing Player Outline RP'
        with zipfile.ZipFile(PACK) as archive:
            for path in root.rglob('*'):
                if path.is_file():
                    self.assertEqual(path.read_bytes(), archive.read(path.relative_to(root).as_posix()))

if __name__ == '__main__':
    unittest.main()
