"""Package the APK and forkable source without caches or local credentials."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(__file__).resolve().parent.parent
out = root / "dist"
out.mkdir(exist_ok=True)
apk = out / "kiro-mobile-debug.apk"
shutil.copyfile(root / "app/build/outputs/apk/debug/app-debug.apk", apk)
excluded = {".git", ".gradle", ".kotlin", ".local", "build", "dist", "node_modules"}
sources = []
for item in root.rglob("*"):
    relative = item.relative_to(root)
    if any(part in excluded for part in relative.parts):
        continue
    if not item.is_file() or not item.resolve().is_relative_to(root):
        continue
    if item.name in {"local.properties", "google-services.json", "config.local.json"}:
        continue
    if "service-account" in item.name or item.suffix in {".keystore", ".jks"}:
        continue
    sources.append(item)
archive = out / "kiro-mobile-source.zip"
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
    for item in sources:
        bundle.write(item, "kiro-mobile/" + item.relative_to(root).as_posix())
checksums = out / "SHA256SUMS.txt"
checksums.write_text("".join(f"{hashlib.sha256(file.read_bytes()).hexdigest()}  {file.name}\n" for file in [apk, archive]), encoding="utf-8")
print(f"APK: {apk.name} ({apk.stat().st_size:,} bytes)")
print(f"Source archive: {archive.name} ({len(sources)} files)")
