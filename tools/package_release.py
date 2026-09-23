"""Create a source-only archive from an explicit public file list."""

import argparse
import hashlib
import pathlib
import re
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
PREFIX = "ClusterNav-v2.9.8/"
SINGLE_FILES = (
    ".gitignore", "LICENSE", "README.md", "HANDOVER.md", "CHANGELOG.md",
    "AndroidManifest.xml", "build.py", "test_android.py", "test_guard.py",
    "test_map_restart.py", "test_reboot.py", "tools/package_release.py",
)
SOURCE_DIRS = ("src", "res", "tests")
ALLOWED_SUFFIXES = {".java", ".xml", ".png"}
PRIVATE_PATTERNS = (
    re.compile(rb"[A-Za-z]:[/\\]Users[/\\]", re.IGNORECASE),
    re.compile(rb"SIGNING_PASSWORD\s*=\s*['\"][^'\"]+['\"]"),
)


def source_files():
    paths = [ROOT / name for name in SINGLE_FILES]
    for dirname in SOURCE_DIRS:
        paths.extend(path for path in (ROOT / dirname).rglob("*") if path.is_file())
    for path in sorted(set(paths), key=lambda p: p.relative_to(ROOT).as_posix()):
        if not path.is_file():
            raise FileNotFoundError(path)
        relative = path.relative_to(ROOT)
        if relative.parts[0] in SOURCE_DIRS and path.suffix.lower() not in ALLOWED_SUFFIXES:
            raise ValueError("Unexpected source asset: " + str(relative))
        if any(part.startswith(".") for part in relative.parts[1:]):
            raise ValueError("Hidden source path: " + str(relative))
        yield path, relative.as_posix()


def checked_data(path):
    data = path.read_bytes()
    if path.suffix.lower() != ".png":
        for pattern in PRIVATE_PATTERNS:
            if pattern.search(data):
                raise ValueError("Private/local data in source: " + str(path))
    return data


def write_entry(archive, name, data):
    info = zipfile.ZipInfo(PREFIX + name, (2026, 9, 23, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    archive.writestr(info, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest = []
    with zipfile.ZipFile(output, "w") as archive:
        for path, relative in source_files():
            data = checked_data(path)
            write_entry(archive, relative, data)
            manifest.append(hashlib.sha256(data).hexdigest() + "  " + relative)
        write_entry(archive, "SOURCE_MANIFEST.sha256", ("\n".join(manifest) + "\n").encode("utf-8"))
    with zipfile.ZipFile(output) as archive:
        bad = archive.testzip()
        if bad:
            raise ValueError("Archive CRC failure: " + bad)
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate path in archive")
    print("SOURCE_ZIP", output)
    print("FILES", len(manifest))
    print("SHA256", hashlib.sha256(output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
