import hashlib
import tempfile
import unittest
from pathlib import Path

from PIL import Image

from build_leon_production import (
    alpha_bounds,
    build_manifest,
    extract_reference_master,
    key_green,
    read_landmarks,
)


class BuildLeonProductionTest(unittest.TestCase):
    def test_landmarks_are_current_master_values(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "leon-front.txt"
            p.write_text("20 619 180\n", encoding="utf-8")
            self.assertEqual((20.0, 619.0, 180.0), read_landmarks(p))

    def test_edge_connected_green_is_transparent_but_internal_green_is_preserved(self):
        im = Image.new("RGBA", (5, 5), (20, 20, 20, 255))
        px = im.load()
        for x in range(5):
            px[x, 0] = (0, 255, 0, 255)
            px[x, 4] = (0, 255, 0, 255)
        for y in range(5):
            px[0, y] = (0, 255, 0, 255)
            px[4, y] = (0, 255, 0, 255)
        px[2, 2] = (0, 255, 0, 255)

        keyed = key_green(im)
        out = keyed.load()
        self.assertEqual(0, out[0, 0][3])
        self.assertEqual(255, out[2, 2][3])

    def test_committed_front_source_extracts_complete_master(self):
        repo_root = Path(__file__).resolve().parents[3]
        source_path = repo_root / "docs" / "leon-reference" / "leon-front-source.png"
        with Image.open(source_path) as source:
            master = extract_reference_master(source)

        self.assertEqual((360, 640), master.size)
        box = master.getchannel("A").getbbox()
        self.assertIsNotNone(box)
        self.assertLessEqual(box[1], 20)
        self.assertGreaterEqual(box[3] - 1, 619)
        self.assertGreater(box[2] - box[0], 150)

    def test_extraction_leaves_no_green_screen_residue(self):
        repo_root = Path(__file__).resolve().parents[3]
        source_path = repo_root / "docs" / "leon-reference" / "leon-front-source.png"
        with Image.open(source_path) as source:
            master = extract_reference_master(source)

        opaque = 0
        spill = 0
        for r, g, b, a in master.getdata():
            if a < 16:
                continue
            opaque += 1
            if g >= 135 and g - max(r, b) >= 70:
                spill += 1
        self.assertGreater(opaque, 0)
        self.assertLess(spill / opaque, 0.0005)

    def test_manifest_pins_source_and_full_body_bounds(self):
        source = Image.new("RGBA", (360, 640), (0, 0, 0, 0))
        px = source.load()
        for y in range(20, 620):
            for x in range(100, 261):
                px[x, y] = (40, 30, 20, 255)
        source_bytes = b"source-bytes"
        manifest = build_manifest(
            source,
            source,
            source_bytes,
            (20.0, 619.0, 180.0),
        )
        self.assertEqual(360, manifest["source_width"])
        self.assertEqual(640, manifest["source_height"])
        self.assertEqual(48, manifest["mesh_cols"])
        self.assertEqual(84, manifest["mesh_rows"])
        self.assertEqual(hashlib.sha256(source_bytes).hexdigest(), manifest["source_sha256"])
        self.assertGreaterEqual(manifest["alpha_bounds"]["bottom"], 619)
        self.assertEqual([384, 768], manifest["design_size"])


if __name__ == "__main__":
    unittest.main()
