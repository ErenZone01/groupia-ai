import unittest
from check_saspay_config import check


class SaspayConfigurationTests(unittest.TestCase):
    def config(self):
        return {"SASPAY_ENABLED": "true", "SASPAY_API_KEY": "sk_test_synthetic",
                "SASPAY_WEBHOOK_SECRET": "synthetic-secret",
                "SASPAY_MERCHANT_ID": "6f1e2b3a-8c4d-4a2f-9e1b-2d3c4f5a6b7c",
                "SASPAY_CHECKOUT_HOSTS": "pay.saspay.me"}

    def test_disabled_requires_no_credentials(self):
        self.assertEqual(check({}), [])

    def test_complete_test_configuration(self):
        self.assertEqual(check(self.config()), [])

    def test_live_key_is_refused_without_echoing_value(self):
        env = self.config()
        env["SASPAY_API_KEY"] = "sk_live_never-use-this-fixture"
        errors = check(env)
        self.assertTrue(errors)
        self.assertNotIn(env["SASPAY_API_KEY"], str(errors))

    def test_wildcard_redirect_domain_is_refused(self):
        env = self.config()
        env["SASPAY_CHECKOUT_HOSTS"] = "*.example.test"
        self.assertTrue(check(env))
