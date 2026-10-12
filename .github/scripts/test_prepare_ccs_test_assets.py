import copy
import hashlib
import os
import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from prepare_ccs_test_assets import BRANCH, REPOSITORY, WORKFLOWS, prepare, validate_pair


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
