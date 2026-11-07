"""Install a checksum-verified local Temporal CLI; never download an implicit test server."""
from __future__ import annotations

import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import platform
import tarfile
from urllib.request import urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LIMIT = 300_000_000


def local_path(path: Path) -> None:
    if not path.resolve().is_relative_to(ROOT.resolve() / ".tools"):
        raise ValueError("TEMPORAL_DESTINATION_INVALID")


def verified_download(url: str, digest: str, cache: Path) -> bytes:
    local_path(cache)
    if cache.is_file():
        content = cache.read_bytes()
    else:
        with urlopen(url, timeout=90) as response:
            content = response.read(LIMIT + 1)
    if len(content) > LIMIT or hashlib.sha256(content).hexdigest() != digest:
        raise ValueError("TEMPORAL_DISTRIBUTION_HASH_MISMATCH")
    cache.parent.mkdir(parents=True, exist_ok=True)
    if not cache.exists():
        cache.write_bytes(content)
    return content


def selected_files(content: bytes, zipped: bool, executable: str) -> dict[str, bytes]:
    """Read selected regular files; archive member paths are never extraction destinations."""
    selected = {}
    archive = zipfile.ZipFile(io.BytesIO(content)) if zipped else tarfile.open(fileobj=io.BytesIO(content))
    with archive:
        for member in archive.infolist() if zipped else archive.getmembers():
            name = member.filename if zipped else member.name
            basename = PurePosixPath(name).name
            if basename != executable and not basename.upper().startswith(("LICENSE", "NOTICE")):
                continue
            regular = not member.is_dir() and ((member.external_attr >> 16) & 0o170000) != 0o120000 if zipped else member.isfile()
            size = member.file_size if zipped else member.size
            if not regular or size > LIMIT or basename in selected or "\\" in basename or ":" in basename:
                raise ValueError("TEMPORAL_DISTRIBUTION_MEMBER_INVALID")
            data = archive.read(member) if zipped else archive.extractfile(member).read()
            selected[basename] = data
    if executable not in selected:
        raise ValueError("TEMPORAL_EXECUTABLE_ABSENT")
    return selected


def main() -> None:
    lock = json.loads((ROOT / "scripts/temporal-toolchain-lock.json").read_text(encoding="utf-8"))
    if platform.machine().lower() not in {"amd64", "x86_64"} or platform.system() not in lock["platforms"]:
        raise ValueError("TEMPORAL_PLATFORM_UNSUPPORTED")
    entry = lock["platforms"][platform.system()]
    cache = ROOT / ".tools/temporal-distributions"
    manifest = verified_download(lock["checksums"]["url"], lock["checksums"]["sha256"], cache / "checksums-1.10.0.txt")
    filename = entry["url"].rsplit("/", 1)[1]
    if not any(line.split() == [entry["sha256"], filename] for line in manifest.decode("utf-8").splitlines()):
        raise ValueError("TEMPORAL_PUBLISHER_CHECKSUM_MISMATCH")
    content = verified_download(entry["url"], entry["sha256"], cache / filename)
    destination = ROOT / ".tools" / ("temporal-" + lock["version"])
    local_path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    for basename, data in selected_files(content, filename.endswith(".zip"), entry["executable"]).items():
        target = destination / basename
        if target.is_symlink() or not target.resolve().is_relative_to(destination.resolve()):
            raise ValueError("TEMPORAL_DESTINATION_INVALID")
        target.write_bytes(data)
        if basename == entry["executable"] and platform.system() != "Windows":
            target.chmod(0o755)
    print("TEMPORAL_CLI_READY")


if __name__ == "__main__":
    main()
