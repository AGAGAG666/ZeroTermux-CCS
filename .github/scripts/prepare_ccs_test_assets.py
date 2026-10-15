"""Validate paired CC Switch CI assets against candidate or release Gradle pins."""
import argparse
import hashlib
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

REPOSITORY = "AGAGAG666/cc-switch"
BRANCH = "android-sidecar"
TRUSTED_EVENTS = ("push", "workflow_dispatch")
WORKFLOWS = {
    "web": ".github/workflows/android-frontend-build.yml",
    "sidecar": ".github/workflows/android-sidecar-build.yml",
}


def validate_pair(web, sidecar, source_sha):
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("source SHA must be a full lowercase Git commit hash")
    for kind, run in (("web", web), ("sidecar", sidecar)):
        if run.get("repository", {}).get("full_name") != REPOSITORY:
            raise ValueError(f"{kind}: unexpected source repository")
        if run.get("head_branch") != BRANCH or run.get("event") not in TRUSTED_EVENTS:
            raise ValueError(f"{kind}: unexpected branch or event")
        if run.get("path", "").split("@", 1)[0] != WORKFLOWS[kind]:
            raise ValueError(f"{kind}: unexpected source workflow")
        if run.get("status") != "completed" or run.get("conclusion") != "success":
            raise ValueError(f"{kind}: source CI has not completed successfully")
        if run.get("head_sha") != source_sha:
            raise ValueError(f"{kind}: source commit mismatch")
        if not isinstance(run.get("id"), int) or run["id"] <= 0:
            raise ValueError(f"{kind}: invalid run ID")


def validate_web(path):
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        names = [entry.filename for entry in entries]
        if "index.html" not in names or len(names) != len(set(names)):
            raise ValueError("Web ZIP must contain exactly one root index.html")
        if len(names) > 4096 or sum(entry.file_size for entry in entries) > 64 * 1024 * 1024:
            raise ValueError("Web ZIP is unexpectedly large")
        if not any(name.startswith("assets/") and not name.endswith("/") for name in names):
            raise ValueError("Web ZIP has no assets")
        for entry in entries:
            name = entry.filename
            if (name != "index.html" and not name.startswith("assets/")) or ".." in Path(name).parts or "\\" in name:
                raise ValueError("Web ZIP contains an unexpected or unsafe path")
            if (entry.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError("Web ZIP contains a symlink")
        if archive.testzip() is not None:
            raise ValueError("Web ZIP CRC verification failed")
        html = archive.read("index.html").decode("utf-8")
        if 'data-ccs="mobile-overrides"' not in html or re.search(r"(?:src|href)=[\"']/", html):
            raise ValueError("Web ZIP is missing mobile styles or relative asset references")
        javascript = [archive.read(name) for name in names if name.endswith(".js")]
        if not any(b"__CCS_SIDECAR__" in code for code in javascript):
            raise ValueError("Web ZIP is missing the Android sidecar bridge")
        if any(b"__TAURI_INTERNALS__" in code for code in javascript):
            raise ValueError("Web ZIP contains desktop Tauri IPC")


def validate_elf(data):
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("sidecar must be a little-endian ELF64 executable")
    kind, machine = struct.unpack_from("<HH", data, 16)
    if kind != 3 or machine != 183:
        raise ValueError("sidecar must be an arm64 position-independent executable")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if entry_size < 56 or count > 256 or offset + entry_size * count > len(data):
        raise ValueError("sidecar program headers are invalid")
    interpreters = []
    for index in range(count):
        entry = offset + index * entry_size
        if struct.unpack_from("<I", data, entry)[0] == 3:
            position = struct.unpack_from("<Q", data, entry + 8)[0]
            size = struct.unpack_from("<Q", data, entry + 32)[0]
            if position + size > len(data):
                raise ValueError("sidecar interpreter is out of bounds")
            interpreters.append(data[position:position + size].rstrip(b"\0"))
    if interpreters != [b"/system/bin/linker64"]:
        raise ValueError("sidecar is not an Android executable")


def validate_gradle(path, source_sha, artifacts):
    if not re.fullmatch(r"[0-9a-f]{40}", source_sha):
        raise ValueError("source SHA must be a full lowercase Git commit hash")
    text = path.read_text()
    tag = re.search(r'ext\.ccsArtifactTag\s*=\s*"([^"]+)"', text)
    allowed_tags = {prefix + source_sha[:8] for prefix in ("ccs-ci-", "ccs-android-")}
    if not tag or tag.group(1) not in allowed_tags:
        raise ValueError("Gradle asset tag must be ccs-ci-<SHA8> or ccs-android-<SHA8> for the CI source commit")
    for name, metadata in artifacts.items():
        pattern = (r'name\s*:\s*"' + re.escape(name) + r'"\s*,\s*dest\s*:\s*"[^"]+"'
                   r'\s*,\s*sha256\s*:\s*"([0-9a-f]{64})"\s*,\s*size\s*:\s*(\d+)L')
        matches = re.findall(pattern, text)
        if matches != [(metadata["sha256"], str(metadata["size"]))]:
            raise ValueError(f"Gradle size/hash mismatch for {name}")


def prepare(web_zip, sidecar, web, sidecar_run, source_sha, output, gradle=None):
    validate_pair(web, sidecar_run, source_sha)
    if web_zip.is_symlink() or sidecar.is_symlink() or output.is_symlink():
        raise ValueError("asset paths must not be symlinks")
    validate_web(web_zip)
    data = {"ccs-web.zip": web_zip.read_bytes(), "libccsidecar.so": sidecar.read_bytes()}
    validate_elf(data["libccsidecar.so"])
    artifacts = {name: {"size": len(content), "sha256": hashlib.sha256(content).hexdigest()}
                 for name, content in data.items()}
    if gradle is not None:
        validate_gradle(gradle, source_sha, artifacts)
    output.mkdir(parents=True, exist_ok=True)
    for name in (*data, "manifest.json"):
        if (output / name).exists():
            raise ValueError("refusing to overwrite existing prepared assets")
    manifest = {"sourceRepository": REPOSITORY, "sourceBranch": BRANCH, "sourceSha": source_sha,
                "webRunId": web["id"], "sidecarRunId": sidecar_run["id"], "artifacts": artifacts}
    for name, content in data.items():
        with (output / name).open("xb") as stream:
            stream.write(content)
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--web-run-json", type=Path, required=True)
    parser.add_argument("--sidecar-run-json", type=Path, required=True)
    parser.add_argument("--source-sha", required=True)
    parser.add_argument("--web-zip", type=Path)
    parser.add_argument("--sidecar", type=Path)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--gradle", type=Path)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()
    web = json.loads(args.web_run_json.read_text())
    sidecar = json.loads(args.sidecar_run_json.read_text())
    if args.validate_only:
        validate_pair(web, sidecar, args.source_sha)
        print("CCS source runs verified")
        return
    if not all((args.web_zip, args.sidecar, args.output_dir)):
        parser.error("preparation requires --web-zip, --sidecar and --output-dir")
    result = prepare(args.web_zip, args.sidecar, web, sidecar, args.source_sha, args.output_dir, args.gradle)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        sys.exit(f"CCS CI assets rejected: {error}")
