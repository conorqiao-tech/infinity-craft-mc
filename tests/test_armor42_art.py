"""Verify the actual fresh art and active references, keeping historical originals."""
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class Armor42ArtTests(unittest.TestCase):
    def test_new_native_atlases_icons_and_references_match_original_raster_source(self):
        spec = importlib.util.spec_from_file_location("armor42_validation", ROOT / "artwork/armor42/validate.py")
        validator = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(validator)
        validator.validate()


if __name__ == "__main__":
    unittest.main()
