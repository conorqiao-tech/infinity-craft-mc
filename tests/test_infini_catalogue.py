"""Owner-confirmed currency amounts and public price surfaces must agree exactly."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]


class InfiniCatalogueTests(unittest.TestCase):
    def test_confirmed_usd_and_fractional_infini_are_exact(self):
        catalogue = json.loads((ROOT / "java/src/main/resources/assets/convergence/infini_catalogue.json").read_text())
        self.assertEqual((catalogue["unitsPerInfini"], catalogue["usdPerInfiniCents"]), (100, 200))
        prices = {"go": (15, 25), "plus": (30, 50), "pro": (50, 100), "ultra": (100, 200), "admin": (250, 1000)}
        actual = {p["id"]: p for p in catalogue["products"]}
        self.assertEqual(set(actual), {tier + "_" + term for tier in prices for term in ("pass", "lifetime")})
        for tier, pair in prices.items():
            for term, dollars in zip(("pass", "lifetime"), pair):
                product = actual[tier + "_" + term]
                self.assertEqual(product["usdCents"], dollars * 100)
                self.assertEqual(product["units"], dollars * 50)
                self.assertEqual(product["staff"], tier == "admin")
        self.assertFalse(catalogue["paymentsEnabled"], "A price configuration must not activate public production checkout")
        self.assertTrue(catalogue["admin"]["requiresSeparateOwnerApproval"])

    def test_website_build_uses_same_catalogue_and_shows_no_live_checkout(self):
        subprocess.run([sys.executable, str(ROOT / "website/build_site.py")], check=True, capture_output=True)
        page = (ROOT / "website/dist/index.html").read_text()
        for tier, monthly, lifetime in (("go",15,25),("plus",30,50),("pro",50,100),("ultra",100,200),("admin",250,1000)):
            section = page.split('data-plan="' + tier + '"',1)[1].split('</article>',1)[0]
            self.assertIn(f'${monthly} <small>USD / 30 days',section)
            self.assertIn(f'${lifetime} USD permanent',section)
            self.assertIn('disabled',section)
        self.assertNotIn('$SUPPORT_PLANS',page)
        self.assertIn('separate owner approval',page)
        self.assertIn('no game menu can charge you real money',page)

    def test_player_guide_has_confirmed_usd_and_infini_prices(self):
        text = (ROOT / "server/SUBSCRIPTIONS.md").read_text()
        for label,monthly,permanent,coins,forever in (("Go",15,25,"7.5","12.5"),("Plus",30,50,"15","25"),("Pro",50,100,"25","50"),("Ultra",100,200,"50","100"),("ADMIN application",250,1000,"125","500")):
            row = next(line for line in text.splitlines() if line.startswith("| " + label + " |"))
            self.assertIn(f"${monthly} USD",row)
            self.assertIn(f"${permanent:,} USD",row)
            self.assertIn(f"{coins} / {forever}",row)
        self.assertIn("It grants no operator rights",text)

    def test_astral_original_artwork_validates_without_prepared_server(self):
        path = ROOT / "artwork/astral43/validate.py"
        spec = importlib.util.spec_from_file_location("astral_art_validation", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        module.validate()


if __name__ == "__main__":
    unittest.main()
