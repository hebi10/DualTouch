import os
import unittest
from unittest.mock import patch

import play_preflight
import release


class PlayPreflightTests(unittest.TestCase):
    SHA = "a" * 40

    def env(self):
        return {
            "CHECK_APPROVED": "true", "GITHUB_REPOSITORY": release.REPO,
            "GITHUB_REF": "refs/heads/main", "EXPECTED_MAIN_SHA": self.SHA,
            "GITHUB_SHA": self.SHA,
        }

    def test_missing_approval_and_wrong_ref_block_before_network(self):
        for override in ({"CHECK_APPROVED": "false"}, {"GITHUB_REF": "refs/heads/feature"}, {"GITHUB_SHA": "b" * 40}):
            with self.subTest(override=override), patch.dict(os.environ, {**self.env(), **override}, clear=True), patch.object(release, "github") as github:
                with self.assertRaises(release.Blocked):
                    play_preflight.guard()
                github.assert_not_called()

    def test_moved_main_blocks(self):
        with patch.dict(os.environ, self.env(), clear=True), patch.object(release, "github", return_value={"object": {"sha": "c" * 40}}):
            with self.assertRaises(release.Blocked):
                play_preflight.guard()

    def exercise(self, version=4, reject_read=False):
        calls = []
        reports = []

        def api(method, url, token, data=None):
            calls.append((method, url))
            if method == "POST" and url.endswith("/edits"):
                return {"id": "test"}
            if method == "GET" and url.endswith("/tracks"):
                return {"tracks": [{"track": "internal", "releases": [{"versionCodes": ["3"], "status": "completed"}]}]}
            if method == "GET" and url.endswith("/bundles"):
                if reject_read:
                    raise release.Blocked("read failed")
                return {"bundles": [{"versionCode": 3}]}
            if method == "GET" and url.endswith("/apks"):
                return {"apks": []}
            if method == "DELETE" and url.endswith("/edits/test"):
                return {}
            self.fail("unexpected Play mutation")

        with patch.object(play_preflight, "guard", return_value=self.SHA), patch.dict(os.environ, {"PLAY_ACCESS_TOKEN": "test-only"}, clear=True), patch.object(release, "request_json", side_effect=api), patch.object(release, "source_version", return_value=(version, "test")), patch.object(release, "write_json", side_effect=lambda path, report: reports.append(report)):
            if version <= 3 or reject_read:
                with self.assertRaises(release.Blocked):
                    play_preflight.check()
            else:
                self.assertTrue(play_preflight.check()["wif_api_read_ok"])
        self.assertEqual(calls[0][0], "POST")
        self.assertEqual(calls[-1][0], "DELETE")
        self.assertFalse(any(method not in ("GET", "POST", "DELETE") or ":commit" in url or "uploadType=" in url for method, url in calls))
        self.assertEqual(sum(method == "POST" for method, _ in calls), 1)
        return reports

    def test_inventory_never_uploads_updates_or_commits(self):
        report = self.exercise()[0]
        self.assertFalse(report["published"])
        self.assertFalse(report["write_permission_verified"])
        self.assertEqual(report["highest_visible_version_code"], 3)

    def test_duplicate_version_blocks_but_keeps_diagnostic(self):
        self.assertFalse(self.exercise(version=3)[0]["candidate_above_visible_inventory"])

    def test_read_failure_deletes_temporary_edit(self):
        self.assertEqual(self.exercise(reject_read=True), [])


if __name__ == "__main__":
    unittest.main()
