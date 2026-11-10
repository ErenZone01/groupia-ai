import unittest
from check_saspay_config import check


class SaspayConfigurationTests(unittest.TestCase):
    def config(self):
        return {"SASPAY_ENABLED": "true", "SASPAY_API_KEY": "sk_test_synthetic",
                "SASPAY_WEBHOOK_SECRET": "synthetic-secret",
                "SASPAY_MERCHANT_ID": "6f1e2b3a-8c4d-4a2f-9e1b-2d3c4f5a6b7c",
                "SASPAY_CHECKOUT_HOSTS": "pay.saspay.me"}

    def live(self):
        env = self.config()
        env.update({
            "SASPAY_API_KEY": "sk_live_synthetic",
            "SASPAY_LIVE_PILOT_ENABLED": "true",
            "SASPAY_LIVE_PILOT_ORGANIZATION_ID": "00000000-0000-0000-0000-000000000001",
            "SASPAY_LIVE_PILOT_MAX_CHARGED_XOF": "2000",
            "SPRING_PROFILES_ACTIVE": "prod",
        })
        return env

    def test_disabled_requires_no_credentials(self):
        self.assertEqual(check({}), [])

    def test_complete_test_configuration(self):
        self.assertEqual(check(self.config()), [])

    def test_live_requires_explicit_pilot_and_prod_and_no_secret_echo(self):
        env = self.config()
        env["SASPAY_API_KEY"] = "sk_live_synthetic"
        errors = check(env)
        self.assertTrue(errors)
        self.assertNotIn(env["SASPAY_API_KEY"], str(errors))
        self.assertEqual(check(self.live()), [])

    def test_live_pilot_requires_single_org_and_strict_cap(self):
        env = self.live()
        env["SASPAY_LIVE_PILOT_ORGANIZATION_ID"] = ""
        self.assertTrue(check(env))
        env = self.live()
        env["SASPAY_LIVE_PILOT_MAX_CHARGED_XOF"] = "5001"
        self.assertTrue(check(env))
        env = self.live()
        env["SPRING_PROFILES_ACTIVE"] = "dev"
        self.assertTrue(check(env))

    def test_fractional_xof_flag_is_explicit_boolean(self):
        env = self.live()
        env["SASPAY_ALLOW_FRACTIONAL_XOF_FEES"] = "true"
        self.assertEqual(check(env), [])
        env["SASPAY_ALLOW_FRACTIONAL_XOF_FEES"] = "sometimes"
        self.assertTrue(check(env))
        env["SASPAY_ALLOW_FRACTIONAL_XOF_FEES"] = "false"
        self.assertEqual(check(env), [])

    def test_wildcard_redirect_domain_is_refused(self):
        env = self.config()
        env["SASPAY_CHECKOUT_HOSTS"] = "*.example.test"
        self.assertTrue(check(env))


if __name__ == "__main__":
    unittest.main()
