import copy
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import release


class ReleaseBoundaryTests(unittest.TestCase):
    def setUp(self):
        self.sha = "a" * 40
        self.digest = "b" * 64
        self.config = {"schema": 1, "reviewed_runtime_digest": self.digest,
                       "device_qa_passed": True, "device_qa_evidence_url": "https://example.com/qa",
                       "accessibility_review_ready": True, "accessibility_demo_url": "https://example.com/demo",
                       "upload_certificate_sha256": "c" * 64, "closed_track_id": "alpha"}
        self.receipt = {"package": release.PACKAGE, "source_sha": self.sha, "version_code": 1,
                        "track": "internal", "committed": True, "readback_completed": True,
                        "aab_sha256": self.digest}
        self.internal = {"track": "internal", "releases": [{"versionCodes": ["1"], "status": "completed"}]}
        self.bundles = {"bundles": [{"versionCode": 1, "sha256": self.digest}]}

    def test_pending_physical_qa_blocks_release(self):
        config = {**self.config, "device_qa_passed": False}
        with self.assertRaises(release.Blocked):
            release.readiness(config, self.digest)

    def test_changed_runtime_invalidates_device_qa(self):
        with self.assertRaises(release.Blocked):
            release.readiness(self.config, "d" * 64)

    def test_missing_demo_or_public_pin_blocks(self):
        for field in ("accessibility_demo_url", "upload_certificate_sha256", "device_qa_evidence_url"):
            with self.subTest(field=field), self.assertRaises(release.Blocked):
                release.readiness({**self.config, field: ""}, self.digest)

    def test_runtime_digest_ignores_docs_and_detects_code_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app/src/main").mkdir(parents=True)
            source = root / "app/src/main/Test.kt"
            source.write_text("original")
            before = release.runtime_digest(root)
            (root / "README.md").write_text("updated document")
            self.assertEqual(before, release.runtime_digest(root))
            source.write_text("changed behavior")
            self.assertNotEqual(before, release.runtime_digest(root))

    def test_wrong_workflow_or_source_run_is_rejected(self):
        run = {"head_sha": self.sha, "head_branch": "main", "event": "workflow_dispatch",
               "path": ".github/workflows/play-bundle.yml", "status": "completed", "conclusion": "success"}
        release.validate_run(run, "play-bundle.yml", self.sha)
        for changed in ({"event": "pull_request"}, {"head_sha": "f" * 40},
                        {"path": ".github/workflows/other.yml"}, {"conclusion": "failure"}):
            with self.subTest(changed=changed), self.assertRaises(release.Blocked):
                release.validate_run({**run, **changed}, "play-bundle.yml", self.sha)

    def test_no_approval_or_non_main_prevents_network_access(self):
        for env in ({"RELEASE_APPROVED": "false"}, {"RELEASE_APPROVED": "true", "GITHUB_REPOSITORY": release.REPO, "GITHUB_REF": "refs/heads/feature"}):
            with patch.dict(os.environ, env, clear=True), patch.object(release, "github") as api:
                with self.assertRaises(release.Blocked):
                    release.guard()
                api.assert_not_called()

    def test_changed_main_prevents_credentials_and_publication(self):
        env = {"RELEASE_APPROVED": "true", "GITHUB_REPOSITORY": release.REPO,
               "GITHUB_REF": "refs/heads/main", "EXPECTED_MAIN_SHA": self.sha, "GITHUB_SHA": self.sha}
        with patch.dict(os.environ, env, clear=True), patch.object(release, "github", return_value={"object": {"sha": "d" * 40}}), patch.object(release, "read_json") as read:
            with self.assertRaises(release.Blocked):
                release.guard()
            read.assert_not_called()

    def test_closed_promotes_only_existing_exact_internal_version(self):
        result = release.closed_payload(self.internal, self.bundles, self.receipt, "alpha", self.digest)
        self.assertEqual(result["track"], "alpha")
        self.assertEqual(result["releases"][0]["versionCodes"], ["1"])
        self.assertEqual(self.receipt["track"], "internal")

    def test_production_and_form_factor_tracks_are_blocked(self):
        for track in ("", "internal", "production", "open", "wear:production"):
            with self.subTest(track=track), self.assertRaises(release.Blocked):
                release.closed_payload(self.internal, self.bundles, self.receipt, track, self.digest)

    def test_closed_rejects_changed_hash_or_uncommitted_internal(self):
        for changed in ({"aab_sha256": "f" * 64}, {"committed": False}, {"readback_completed": False}):
            with self.subTest(changed=changed), self.assertRaises(release.Blocked):
                release.closed_payload(self.internal, self.bundles, {**self.receipt, **changed}, "alpha", self.digest)

    def test_closed_rejects_draft_or_mismatched_play_bundle(self):
        internal = copy.deepcopy(self.internal)
        internal["releases"][0]["status"] = "draft"
        with self.assertRaises(release.Blocked):
            release.closed_payload(internal, self.bundles, self.receipt, "alpha", self.digest)
        with self.assertRaises(release.Blocked):
            release.closed_payload(self.internal, {"bundles": [{"versionCode": 1, "sha256": "e" * 64}]}, self.receipt, "alpha", self.digest)

    def test_cleanup_refuses_unrelated_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            env = {"RUNNER_TEMP": directory, "DUALTOUCH_SIGNING_PROPERTIES": directory + "/unrelated/signing.properties"}
            with patch.dict(os.environ, env), self.assertRaises(release.Blocked):
                release.cleanup()

    def test_closed_network_path_never_uploads_a_bundle(self):
        calls = []
        def api(method, url, token, data=None, binary=False):
            calls.append((method, url))
            if method == "POST" and url.endswith("/edits"):
                return {"id": "edit"}
            if url.endswith("/tracks"):
                return {"tracks": [{"track": "alpha"}, {"track": "internal"}]}
            if url.endswith("/tracks/internal"):
                return self.internal
            if url.endswith("/bundles"):
                return self.bundles
            if method == "GET" and url.endswith("/tracks/alpha"):
                return self.internal
            return {}
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {"SOURCE_RUN_ID": "123", "AAB_SHA256": self.digest, "PLAY_ACCESS_TOKEN": "test-only"}), patch.object(release, "guard", return_value=(self.sha, self.config)), patch.object(release, "validate_run"), patch.object(release, "github", return_value={}), patch.object(release, "read_json", return_value=self.receipt), patch.object(release, "request_json", side_effect=api), patch.object(release, "write_json"):
            release.play("closed")
        self.assertFalse(any(method == "POST" and "/bundles" in url for method, url in calls))
        self.assertFalse(any("production" in url for _, url in calls))
        self.assertTrue(any(url.endswith(":commit") for _, url in calls))


if __name__ == "__main__":
    unittest.main()
