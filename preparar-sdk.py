"""Fetch only the two Google SDK archives authorized for Casa; verify before extracting."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

BASE = "https://dl.google.com/android/repository/"
ROOT = Path.home() / "AndroidDev"
DOWNLOADS = ROOT / "dl" / "casa"
SDK = ROOT / "sdk"
WANTED = {
    "platform-35_r02.zip": ("platforms;android-35", SDK / "platforms" / "android-35"),
    "build-tools_r35_windows.zip": ("build-tools;35.0.0", SDK / "build-tools" / "35.0.0"),
}


def install(row):
    name, dest, size, expected = row
    local = DOWNLOADS / name
    def valid(path):
        if not path.exists() or path.stat().st_size != size:
            return False
        with path.open("rb") as f:
            return hashlib.file_digest(f, "sha1").hexdigest() == expected
    if not valid(local):
        partial = local.with_suffix(".partial")
        print("Baixando", name, size, "bytes", flush=True)
        with urllib.request.urlopen(BASE + name, timeout=30) as source, partial.open("wb") as out:
            shutil.copyfileobj(source, out, length=1024 * 1024)
        if not valid(partial):
            raise RuntimeError("Arquivo nao corresponde ao tamanho/hash publicado pelo Google: " + name)
        partial.replace(local)
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(local) as z:
        # Each SDK archive has one wrapper directory; do not trust member paths.
        roots = {PurePosixPath(n).parts[0] for n in z.namelist() if PurePosixPath(n).parts}
        if len(roots) != 1:
            raise RuntimeError("Estrutura inesperada: " + name)
        for member in z.infolist():
            path = PurePosixPath(member.filename)
            if path.is_absolute() or ".." in path.parts or any(":" in part for part in path.parts):
                raise RuntimeError("Caminho inseguro no pacote")
            if (member.external_attr >> 16) & 0o170000 == 0o120000:
                raise RuntimeError("Link nao esperado no pacote")
            rel = path.parts[1:]
            if not rel:
                continue
            target = dest.joinpath(*rel)
            if not target.resolve().is_relative_to(dest.resolve()):
                raise RuntimeError("Caminho fora do SDK")
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with z.open(member) as src, target.open("wb") as out:
                    shutil.copyfileobj(src, out)
    with local.open("rb") as f:
        sha256 = hashlib.file_digest(f, "sha256").hexdigest()
    print("Conferido e extraido:", name, flush=True)
    return {"file": name, "source": BASE + name, "bytes": size, "sha1": expected,
            "sha256": sha256, "destination": str(dest)}


def main():
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    xml = ET.fromstring(urllib.request.urlopen(BASE + "repository2-1.xml", timeout=20).read())
    rows = []
    for package in xml.findall("remotePackage"):
        for archive in package.findall("archives/archive"):
            name = archive.findtext("complete/url", "")
            if name in WANTED:
                expected_package, dest = WANTED[name]
                if package.attrib["path"] != expected_package:
                    raise RuntimeError("Pacote inesperado")
                rows.append((name, dest, int(archive.findtext("complete/size")), archive.findtext("complete/checksum")))
    if len(rows) != len(WANTED):
        raise RuntimeError("Nao foram encontrados exatamente os dois arquivos autorizados")
    with ThreadPoolExecutor(max_workers=2) as pool:
        result = list(pool.map(install, rows))
    report = Path(__file__).parent / "local" / "sdk-verificado.json"
    report.parent.mkdir(exist_ok=True)
    report.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print("SDK pronto. Relatorio:", report, flush=True)


if __name__ == "__main__":
    main()
