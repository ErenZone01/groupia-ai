"""Network-free strict dependency audit gate tests."""
import unittest
from check_frontend_audit import inspect, vulnerability_roots


def entry(severity, via, fix=False):
    return {"severity": severity, "via": via, "fixAvailable": fix}


PICOMATCH = {"name": "picomatch", "severity": "high",
             "url": "https://github.com/advisories/GHSA-fc5q-39xc-vjpf"}


class FrontendAuditTests(unittest.TestCase):
    def sample(self):
        return {
            "vulnerabilities": {
                "picomatch": entry("high", [PICOMATCH]),
                "@angular/build": entry("high", ["picomatch"]),
                "eslint": entry("moderate", ["minimatch"]),
                "minimatch": entry("moderate", [
                    {"name": "minimatch", "severity": "moderate",
                     "url": "https://github.com/advisories/GHSA-g3hv-75wg-m249"}
                ]),
            }
        }

    def test_known_unfixed_dev_only_issue_is_reported(self):
        waived, production, full = inspect(self.sample(), {"vulnerabilities": {}})
        self.assertEqual(waived, ["@angular/build", "picomatch"])
        self.assertEqual((production, full), (0, 4))

    def test_any_production_high_always_fails(self):
        with self.assertRaisesRegex(ValueError, "Production high"):
            inspect(self.sample(), {"vulnerabilities": {
                "picomatch": entry("high", [PICOMATCH])
            }})

    def test_unrelated_high_advisory_always_fails(self):
        report = self.sample()
        report["vulnerabilities"]["bad-lib"] = entry(
            "high", [{"name": "bad-lib", "severity": "high",
                      "url": "https://github.com/advisories/GHSA-unknown-high"}])
        with self.assertRaisesRegex(ValueError, "Unapproved high"):
            inspect(report, {"vulnerabilities": {}})

    def test_fix_available_requires_repair(self):
        report = self.sample()
        report["vulnerabilities"]["picomatch"]["fixAvailable"] = {"name": "picomatch", "version": "4.0.8"}
        with self.assertRaisesRegex(ValueError, "fix now available"):
            inspect(report, {"vulnerabilities": {}})

    def test_unknown_fix_status_requires_investigation(self):
        report = self.sample()
        del report["vulnerabilities"]["picomatch"]["fixAvailable"]
        with self.assertRaisesRegex(ValueError, "status unknown"):
            inspect(report, {"vulnerabilities": {}})

    def test_unresolved_graph_and_cycles_fail(self):
        report = self.sample()
        report["vulnerabilities"]["@angular/build"]["via"] = ["unknown-package"]
        with self.assertRaisesRegex(ValueError, "dependency missing"):
            inspect(report, {"vulnerabilities": {}})
        report["vulnerabilities"]["@angular/build"]["via"] = ["picomatch"]
        report["vulnerabilities"]["picomatch"]["via"] = ["@angular/build"]
        with self.assertRaisesRegex(ValueError, "dependency cycle"):
            inspect(report, {"vulnerabilities": {}})

    def test_missing_or_invalid_results_fail_closed(self):
        for report in ({}, {"error": {"code": "ENOAUDIT"}},
                       {"vulnerabilities": {"a": {"severity": "high", "via": "picomatch"}}}):
            with self.subTest(report=report), self.assertRaises(ValueError):
                inspect(report, {"vulnerabilities": {}})

    def test_clean_audit_without_exception_passes(self):
        self.assertEqual(inspect({"vulnerabilities": {}}, {"vulnerabilities": {}}),
                         ([], 0, 0))


if __name__ == "__main__":
    unittest.main()
