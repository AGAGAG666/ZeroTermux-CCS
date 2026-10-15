import copy
import hashlib
import json
import os
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

from prepare_ccs_test_assets import (
    BRANCH, REPOSITORY, WORKFLOWS, prepare, validate_gradle, validate_pair,
)


class AssetPreparationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR"))
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.sha = "a" * 40
        self.web = {"id": 1, "repository": {"full_name": REPOSITORY}, "head_branch": BRANCH,
                    "event": "workflow_dispatch", "path": WORKFLOWS["web"], "status": "completed",
                    "conclusion": "success", "head_sha": self.sha}
        self.native = copy.deepcopy(self.web)
        self.native.update(id=2, path=WORKFLOWS["sidecar"])
        self.zip = self.root / "web.zip"
        self.write_zip({"index.html": '<style data-ccs="mobile-overrides"></style>',
                        "assets/main.js": 'window.__CCS_SIDECAR__ = {};'} )
        self.bin = self.root / "cc-switch"
        self.write_elf(b"/system/bin/linker64\0")
        self.output = self.root / "prepared"

    def write_zip(self, files):
        with zipfile.ZipFile(self.zip, "w", zipfile.ZIP_DEFLATED) as archive:
            for name, text in files.items():
                archive.writestr(name, text)

    def write_elf(self, interpreter):
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<HH", header, 16, 3, 183)
        struct.pack_into("<Q", header, 32, 64)
        struct.pack_into("<HH", header, 54, 56, 1)
        program = struct.pack("<IIQQQQQQ", 3, 4, 120, 0, 0, len(interpreter), len(interpreter), 1)
        self.bin.write_bytes(header + program + interpreter)

    def prepare(self, gradle=None):
        return prepare(self.zip, self.bin, self.web, self.native, self.sha, self.output, gradle)

    def artifacts(self):
        return {name: {"sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                       "size": path.stat().st_size}
                for name, path in (("ccs-web.zip", self.zip), ("libccsidecar.so", self.bin))}

    def write_gradle(self, tag, artifacts=None):
        artifacts = self.artifacts() if artifacts is None else artifacts
        gradle = self.root / "build.gradle"
        lines = [f'ext.ccsArtifactTag = "{tag}"', 'ext.ccsArtifacts = [']
        for name, metadata in artifacts.items():
            lines.append(f'[name: "{name}", dest: "ccs-artifacts/{name}", '
                         f'sha256: "{metadata["sha256"]}", size: {metadata["size"]}L],')
        gradle.write_text('\n'.join(lines + [']']))
        return gradle

    def test_trusts_only_formal_source_branch_and_repository(self):
        self.assertEqual(BRANCH, "android-sidecar")
        self.assertEqual(REPOSITORY, "AGAGAG666/cc-switch")
        self.assertEqual(WORKFLOWS, {
            "web": ".github/workflows/android-frontend-build.yml",
            "sidecar": ".github/workflows/android-sidecar-build.yml",
        })

    def test_accepts_push_dispatch_and_mixed_pairs(self):
        for web_event in ("push", "workflow_dispatch"):
            for sidecar_event in ("push", "workflow_dispatch"):
                with self.subTest(web=web_event, sidecar=sidecar_event):
                    self.web["event"] = web_event
                    self.native["event"] = sidecar_event
                    validate_pair(self.web, self.native, self.sha)

    def test_rejects_untrusted_run_metadata_on_either_asset(self):
        cases = {
            "head_branch": ("android-sidecar-v4-test", "main", "test/ccs-v4.0.9",
                            "refs/heads/android-sidecar", "", None),
            "event": ("pull_request", "pull_request_target", "workflow_run", "workflow_call",
                      "schedule", "release", "repository_dispatch", "", None),
            "repository": ({"full_name": "someone/cc-switch"},
                           {"full_name": "AGAGAG666/ZeroTermux-CCS"}, {},),
            "head_sha": ("b" * 40, "a" * 39 + "b", "a" * 8, "A" * 40, "", None),
            "status": ("queued", "in_progress", "waiting", "", None),
            "conclusion": ("failure", "cancelled", "skipped", "neutral", "timed_out", "", None),
            "path": (".github/workflows/other.yml", ""),
            "id": (0, -1, "1", None),
        }
        for kind in ("web", "sidecar"):
            for field, values in cases.items():
                for value in (*values, "MISSING"):
                    with self.subTest(kind=kind, field=field, value=value):
                        web, native = copy.deepcopy(self.web), copy.deepcopy(self.native)
                        run = web if kind == "web" else native
                        if value == "MISSING":
                            run.pop(field)
                        else:
                            run[field] = value
                        with self.assertRaises(ValueError):
                            validate_pair(web, native, self.sha)

    def test_rejects_non_full_or_non_lowercase_source_sha(self):
        for sha in ("", "a" * 8, "a" * 39, "a" * 41, "A" * 40, "g" * 40,
                    "a" * 40 + "\n"):
            with self.subTest(sha=sha):
                self.web["head_sha"] = self.native["head_sha"] = sha
                with self.assertRaisesRegex(ValueError, "full lowercase"):
                    validate_pair(self.web, self.native, sha)
                gradle = self.write_gradle("ccs-android-" + sha[:8])
                with self.assertRaisesRegex(ValueError, "full lowercase"):
                    validate_gradle(gradle, sha, self.artifacts())

    def test_accepts_workflow_path_with_ref_suffix(self):
        self.web["path"] += "@refs/heads/android-sidecar"
        self.native["path"] += "@refs/heads/android-sidecar"
        validate_pair(self.web, self.native, self.sha)

    def test_candidate_and_formal_tags_preserve_identical_bytes(self):
        manifests = []
        for prefix in ("ccs-ci-", "ccs-android-"):
            with self.subTest(prefix=prefix):
                self.output = self.root / prefix
                gradle = self.write_gradle(prefix + self.sha[:8])
                self.web["event"] = self.native["event"] = "push"
                manifest = self.prepare(gradle)
                manifests.append(manifest)
                self.assertEqual(manifest["sourceBranch"], "android-sidecar")
                self.assertEqual(manifest["sourceSha"], self.sha)
                self.assertEqual(manifest["artifacts"], self.artifacts())
                self.assertEqual((self.output / "ccs-web.zip").read_bytes(), self.zip.read_bytes())
                self.assertEqual((self.output / "libccsidecar.so").read_bytes(), self.bin.read_bytes())
        self.assertEqual(*manifests)

    def test_rejects_other_tag_prefixes_suffixes_and_commits(self):
        for tag in ("ccs-aaaaaaaa", "ccs-ci-bbbbbbbb", "ccs-android-bbbbbbbb",
                    "ccs-android-aaaaaaa", "ccs-android-aaaaaaaaa", "ccs-android-AAAAAAAA",
                    "ccs-android-aaaaaaaa-extra", "ccs-ci-aaaaaaaa-extra",
                    "refs/tags/ccs-android-aaaaaaaa", "ccs-android-" + self.sha, ""):
            with self.subTest(tag=tag):
                with self.assertRaisesRegex(ValueError, "asset tag"):
                    self.prepare(self.write_gradle(tag))
                self.assertFalse(self.output.exists())

    def test_rejects_missing_gradle_tag(self):
        gradle = self.write_gradle("ccs-android-" + self.sha[:8])
        gradle.write_text('\n'.join(gradle.read_text().splitlines()[1:]))
        with self.assertRaisesRegex(ValueError, "asset tag"):
            self.prepare(gradle)
        self.assertFalse(self.output.exists())

    def test_both_tags_reject_wrong_hash_or_size_for_either_asset(self):
        for prefix in ("ccs-ci-", "ccs-android-"):
            for name in self.artifacts():
                for field in ("sha256", "size"):
                    with self.subTest(prefix=prefix, name=name, field=field):
                        artifacts = self.artifacts()
                        artifacts[name][field] = "0" * 64 if field == "sha256" else artifacts[name][field] + 1
                        with self.assertRaisesRegex(ValueError, "size/hash mismatch"):
                            self.prepare(self.write_gradle(prefix + self.sha[:8], artifacts))
                        self.assertFalse(self.output.exists())

    def test_rejects_same_size_changed_bytes_under_formal_tag(self):
        gradle = self.write_gradle("ccs-android-" + self.sha[:8])
        original = self.bin.read_bytes()
        changed = bytearray(original)
        changed[48] = 1  # ELF flags; preserve the valid Android interpreter and ELF layout.
        self.bin.write_bytes(changed)
        self.assertEqual(len(changed), len(original))
        with self.assertRaisesRegex(ValueError, "size/hash mismatch for libccsidecar.so"):
            self.prepare(gradle)
        self.assertFalse(self.output.exists())

    def test_rejects_missing_or_duplicate_asset_pins(self):
        for name in self.artifacts():
            for duplicate in (False, True):
                with self.subTest(name=name, duplicate=duplicate):
                    gradle = self.write_gradle("ccs-android-" + self.sha[:8])
                    text = gradle.read_text()
                    line = next(line for line in text.splitlines() if f'name: "{name}"' in line)
                    gradle.write_text(text.replace(line, line + '\n' + line if duplicate else ''))
                    with self.assertRaisesRegex(ValueError, "size/hash mismatch"):
                        self.prepare(gradle)
                    self.assertFalse(self.output.exists())

    def run_cli(self, *arguments):
        web_json = self.root / "web-run.json"
        native_json = self.root / "sidecar-run.json"
        web_json.write_text(json.dumps(self.web))
        native_json.write_text(json.dumps(self.native))
        return subprocess.run([
            sys.executable, str(Path(__file__).with_name("prepare_ccs_test_assets.py").resolve()),
            "--web-run-json", str(web_json), "--sidecar-run-json", str(native_json),
            "--source-sha", self.sha, *map(str, arguments),
        ], capture_output=True, text=True, timeout=10)

    def test_cli_accepts_both_tags_with_full_nonuniform_sha(self):
        self.sha = "0123456789abcdef" * 2 + "12345678"
        self.web["head_sha"] = self.native["head_sha"] = self.sha
        self.web["event"] = "push"
        for prefix in ("ccs-ci-", "ccs-android-"):
            with self.subTest(prefix=prefix):
                self.output = self.root / prefix
                gradle = self.write_gradle(prefix + self.sha[:8])
                result = self.run_cli("--web-zip", self.zip, "--sidecar", self.bin,
                                      "--output-dir", self.output, "--gradle", gradle)
                self.assertEqual(result.returncode, 0, result.stderr)
                manifest = json.loads(result.stdout)
                self.assertEqual(manifest, json.loads((self.output / "manifest.json").read_text()))
                self.assertEqual(manifest["sourceSha"], self.sha)
                self.assertEqual(manifest["artifacts"], self.artifacts())

    def test_cli_rejects_formal_tag_hash_mismatch_without_outputs(self):
        artifacts = self.artifacts()
        artifacts["ccs-web.zip"]["sha256"] = "0" * 64
        gradle = self.write_gradle("ccs-android-" + self.sha[:8], artifacts)
        result = self.run_cli("--web-zip", self.zip, "--sidecar", self.bin,
                              "--output-dir", self.output, "--gradle", gradle)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CCS CI assets rejected: Gradle size/hash mismatch", result.stderr)
        self.assertFalse(self.output.exists())

    def test_cli_validate_only_checks_metadata_without_assets(self):
        self.web["event"] = "push"
        accepted = self.run_cli("--validate-only")
        self.assertEqual(accepted.returncode, 0, accepted.stderr)
        self.assertIn("CCS source runs verified", accepted.stdout)
        self.native["head_branch"] = "android-sidecar-v4-test"
        rejected = self.run_cli("--validate-only")
        self.assertNotEqual(rejected.returncode, 0)
        self.assertIn("unexpected branch or event", rejected.stderr)
        self.assertFalse(self.output.exists())

    def test_accepts_and_preserves_ci_bytes(self):
        manifest = self.prepare()
        self.assertEqual((self.output / "ccs-web.zip").read_bytes(), self.zip.read_bytes())
        self.assertEqual((self.output / "libccsidecar.so").read_bytes(), self.bin.read_bytes())
        self.assertEqual(manifest["artifacts"]["ccs-web.zip"]["sha256"], hashlib.sha256(self.zip.read_bytes()).hexdigest())

    def test_rejects_different_commits(self):
        self.native["head_sha"] = "b" * 40
        with self.assertRaisesRegex(ValueError, "commit mismatch"):
            self.prepare()
        self.assertFalse(self.output.exists())

    def test_rejects_failed_ci(self):
        self.web["conclusion"] = "failure"
        with self.assertRaisesRegex(ValueError, "successfully"):
            self.prepare()

    def test_rejects_wrong_workflow(self):
        self.native["path"] = WORKFLOWS["web"]
        with self.assertRaisesRegex(ValueError, "workflow"):
            validate_pair(self.web, self.native, self.sha)

    def test_rejects_nested_web_root(self):
        self.write_zip({"dist-android/index.html": "fixture"})
        with self.assertRaisesRegex(ValueError, "root index"):
            self.prepare()

    def test_rejects_zip_traversal(self):
        self.write_zip({"index.html": "fixture", "assets/../outside.js": "fixture"})
        with self.assertRaisesRegex(ValueError, "unsafe path"):
            self.prepare()

    def test_rejects_non_android_interpreter(self):
        self.write_elf(b"/lib/ld-linux-aarch64.so.1\0")
        with self.assertRaisesRegex(ValueError, "Android executable"):
            self.prepare()

    def test_refuses_existing_outputs(self):
        self.output.mkdir()
        (self.output / "ccs-web.zip").write_bytes(b"existing fixture")
        with self.assertRaisesRegex(ValueError, "overwrite"):
            self.prepare()
        self.assertEqual((self.output / "ccs-web.zip").read_bytes(), b"existing fixture")

    def test_rejects_mismatching_gradle_pins(self):
        gradle = self.root / "build.gradle"
        gradle.write_text('ext.ccsArtifactTag = "ccs-ci-aaaaaaaa"\n')
        with self.assertRaisesRegex(ValueError, "size/hash mismatch"):
            self.prepare(gradle)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
