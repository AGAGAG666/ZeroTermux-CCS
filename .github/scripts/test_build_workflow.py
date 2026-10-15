"""Offline guards for build.yml's current layout; intentionally not a YAML parser."""
import itertools
import os
import re
import shlex
import subprocess
import sys
import tempfile
import textwrap
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


WORKFLOW = Path(__file__).resolve().parents[1] / "workflows" / "build.yml"
SQL_REPORTS = (
    "CcsSqlImportCacheTest", "CcsSqlImportFileTest",
    "CcsSqlImportLifecycleTest", "CcsSqlImportTaskTest",
)


def steps_from(text):
    """Read only the repository's named, six-space-indented build steps."""
    blocks = re.split(r"^      - name: ", text, flags=re.MULTILINE)[1:]
    return {block.splitlines()[0]: block for block in blocks}


def field(step, key, indent=8):
    matches = re.findall(r"^" + " " * indent + re.escape(key) + r": (.*)$",
                         step, flags=re.MULTILINE)
    if len(matches) > 1:
        raise AssertionError(f"duplicate step field: {key}")
    return matches[0] if matches else None


def script(step):
    run = field(step, "run")
    if run not in ("|", ">-"):
        return run
    lines = step.splitlines()
    start = lines.index("        run: " + run) + 1
    body = []
    for line in lines[start:]:
        if line and not line.startswith("          "):
            break
        body.append(line)
    content = textwrap.dedent("\n".join(body)).strip()
    return " ".join(content.splitlines()) if run == ">-" else content


class BuildWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = WORKFLOW.read_text()
        cls.steps = steps_from(cls.text)

    def step(self, name):
        self.assertIn(name, self.steps)
        return self.steps[name]

    def assert_required(self, step):
        self.assertIsNone(field(step, "if"), "gate must run on every build")
        self.assertIsNone(field(step, "continue-on-error"), "gate must fail closed")

    def test_no_test_branch_or_job_level_bypass(self):
        self.assertNotIn("test/ccs-v4", self.text)
        self.assertNotIn("github.ref", self.text)
        self.assertNotRegex(self.text, r"(?m)^    (?:if|continue-on-error):")

    def test_asset_and_workflow_tests_are_unconditional(self):
        step = self.step("测试 CCS CI 资产校验逻辑")
        self.assert_required(step)
        command = shlex.split(script(step))
        self.assertEqual(command, ["python3", "-m", "unittest", "discover", "-s",
                                   ".github/scripts", "-p", "test_*.py", "-v"])
        self.assertEqual(field(step, "PYTHONDONTWRITEBYTECODE", 10), "'1'")
        self.assertEqual(field(step, "TMPDIR", 10), "${{ runner.temp }}")

    def test_any_ci_input_enables_validation_and_no_input_uses_release(self):
        step = self.step("接入同提交 CCS CI 资产")
        self.assertEqual(field(step, "if"),
                         "${{ inputs.ccs_web_run != '' || inputs.ccs_sidecar_run != '' || inputs.ccs_source_sha != '' }}")
        self.assertIsNone(field(step, "continue-on-error"))
        for name, input_name in (("CCS_WEB_RUN", "ccs_web_run"),
                                 ("CCS_SIDECAR_RUN", "ccs_sidecar_run"),
                                 ("CCS_SOURCE_SHA", "ccs_source_sha"), ("CCS_ARCH", "arch")):
            self.assertEqual(field(step, name, 10), "${{ inputs." + input_name + " }}")
        # An override may only be exported by the optional, validated input step.
        self.assertEqual(self.text.count("CCS_ARTIFACT_DIR="), 1)
        self.assertIn('echo "CCS_ARTIFACT_DIR=$work/prepared" >> "$GITHUB_ENV"', script(step))
        remaining = self.text.replace("      - name: " + step, "", 1)
        self.assertNotIn("CCS_ARTIFACT_DIR", remaining)
        self.assertNotIn("ccs-ci-", remaining)

    def input_guard(self):
        step = self.step("接入同提交 CCS CI 资产")
        self.assertEqual(field(step, "shell"), "bash")
        command = script(step)
        self.assertIn('work="$RUNNER_TEMP/ccs-ci-input"', command)
        # Run only the actual shell guard, before mkdir, gh or any network operation.
        guard = command.split('work="$RUNNER_TEMP/ccs-ci-input"', 1)[0]
        self.assertTrue(guard.startswith("set -euo pipefail\n"))
        self.assertNotIn("gh ", guard)
        return guard

    def run_input_guard(self, values):
        env = os.environ.copy()
        env.update(dict(zip(("CCS_WEB_RUN", "CCS_SIDECAR_RUN", "CCS_SOURCE_SHA", "CCS_ARCH"), values)))
        return subprocess.run(["bash", "-c", self.input_guard()], env=env,
                              capture_output=True, text=True, timeout=10).returncode

    def test_partial_inputs_and_non_arm64_fail_before_download(self):
        for present in itertools.product((False, True), repeat=3):
            for arch in ("arm64", "all", "universal", "arm", "x86", "x86_64", ""):
                with self.subTest(present=present, arch=arch):
                    values = [value if enabled else "" for value, enabled in
                              zip(("123", "456", "a" * 40), present)]
                    accepted = self.run_input_guard((*values, arch)) == 0
                    self.assertEqual(accepted, all(present) and arch == "arm64")
        # All-empty inputs skip the step via its OR condition (checked above).

    def test_malformed_ci_inputs_fail_before_download(self):
        for index, invalid in ((0, "abc"), (1, "1; true"), (2, "a" * 8),
                               (2, "A" * 40), (2, "a" * 40 + "\n")):
            with self.subTest(index=index, invalid=invalid):
                values = ["123", "456", "a" * 40, "arm64"]
                values[index] = invalid
                self.assertNotEqual(self.run_input_guard(values), 0)

    def test_metadata_is_validated_before_download_and_gradle_before_export(self):
        command = script(self.step("接入同提交 CCS CI 资产"))
        self.assertEqual(command.count('gh api "repos/AGAGAG666/cc-switch/actions/runs/'), 2)
        self.assertEqual(command.count("-R AGAGAG666/cc-switch"), 2)
        self.assertIn("--name ccs-android-web", command)
        self.assertIn("--name ccs-sidecar_arm64-v8a", command)
        self.assertEqual(command.count("python3 .github/scripts/prepare_ccs_test_assets.py"), 2)
        self.assertLess(command.index("--validate-only"), command.index("gh run download"))
        self.assertLess(command.rindex("gh run download"), command.index("--gradle app/build.gradle"))
        self.assertLess(command.index("--gradle app/build.gradle"), command.index("CCS_ARTIFACT_DIR="))

    def test_sql_tests_and_report_gate_precede_all_apk_builds(self):
        tests = self.step("SQL 系统文件选择专项单测")
        reports = self.step("核对 SQL 专项单测报告")
        self.assert_required(tests)
        self.assert_required(reports)
        self.assertEqual(shlex.split(script(tests)), ["./gradlew", "--build-cache", "--stacktrace",
                         ":app:testReleaseUnitTest", "--tests",
                         "com.termux.zerocore.ccs.CcsSqlImport*Test", "-Darch=arm64"])
        names = list(self.steps)
        self.assertLess(names.index("测试 CCS CI 资产校验逻辑"), names.index("接入同提交 CCS CI 资产"))
        self.assertLess(names.index("SQL 系统文件选择专项单测"), names.index("核对 SQL 专项单测报告"))
        self.assertLess(names.index("核对 SQL 专项单测报告"), names.index("编译 APK (${{ inputs.arch }})"))
        for name in names:
            if name.startswith("上传 "):
                self.assertLess(names.index("编译 APK (${{ inputs.arch }})"), names.index(name))

    def run_report_gate(self, counts, overrides=None, malformed=False):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as directory:
            root = Path(directory)
            reports = root / "app/build/test-results/testReleaseUnitTest"
            reports.mkdir(parents=True)
            for index, count in enumerate(counts):
                attrs = {"tests": str(count), "failures": "0", "errors": "0", "skipped": "0"}
                if index == 0 and overrides:
                    attrs.update(overrides)
                name = SQL_REPORTS[index] if index < len(SQL_REPORTS) else "CcsSqlImportExtraTest"
                report = reports / f"TEST-com.termux.zerocore.ccs.{name}.xml"
                report.write_text("<broken" if malformed else ET.tostring(ET.Element("testsuite", attrs), encoding="unicode"))
            command = shlex.split(script(self.step("核对 SQL 专项单测报告")))
            self.assertEqual(command[:2], ["python3", "-c"])
            return subprocess.run([sys.executable, *command[1:]], cwd=root,
                                  capture_output=True, text=True, timeout=10)

    def test_sql_report_gate_accepts_four_reports_and_39_passes(self):
        result = self.run_report_gate((10, 10, 10, 9))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SQL_TEST_RESULTS=", result.stdout)

    def test_sql_report_gate_rejects_missing_extra_or_wrong_totals(self):
        for counts in ((), (13, 13, 13), (10, 10, 10, 9, 0), (10, 10, 10, 8), (10, 10, 10, 10)):
            with self.subTest(counts=counts):
                self.assertNotEqual(self.run_report_gate(counts).returncode, 0)

    def test_sql_report_gate_rejects_failures_errors_skips_and_bad_xml(self):
        for key in ("failures", "errors", "skipped"):
            with self.subTest(key=key):
                self.assertNotEqual(self.run_report_gate((10, 10, 10, 9), {key: "1"}).returncode, 0)
        self.assertNotEqual(self.run_report_gate((10, 10, 10, 9), malformed=True).returncode, 0)

    def test_release_uploads_fail_closed_and_debug_policy_is_preserved(self):
        for mode in ("Release", "Debug"):
            for abi in ("${{ steps.abi.outputs.abi }}", "universal", "armeabi-v7a", "arm64-v8a", "x86", "x86_64"):
                with self.subTest(mode=mode, abi=abi):
                    step = self.step(f"上传 {mode} APK ({abi})")
                    dynamic = abi.startswith("${{")
                    condition = "!= 'all'" if dynamic else "== 'all'"
                    self.assertEqual(field(step, "if"),
                                     "${{ inputs.build_" + mode.lower() + " && inputs.arch " + condition + " }}")
                    self.assertEqual(field(step, "uses"), "actions/upload-artifact@v7")
                    self.assertEqual(field(step, "continue-on-error"), None if mode == "Release" else "true")
                    self.assertEqual(field(step, "if-no-files-found", 10), "error" if mode == "Release" else "ignore")
                    suffix = "*.apk" if dynamic else f"*_{abi}.apk"
                    self.assertEqual(field(step, "path", 10), f"app/build/outputs/apk/{mode.lower()}/{suffix}")
                    self.assertEqual(field(step, "name", 10), f"ZeroTermux-Nightly_{mode.lower()}_{abi}")


if __name__ == "__main__":
    unittest.main()
