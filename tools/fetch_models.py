"""Download and SHA-256 verify the two ONNX models bundled by the research APK."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "app/src/main/assets"
OPENCV_REV = os.environ.get(
    "OPENCV_ZOO_REV", "47534e27c9851bb1128ccc0102f1145e27f23f98"
)
ANTELOPE_URL = (
    "https://github.com/deepinsight/insightface/releases/download/"
    "model-zoo/antelopev2.zip"
)
ANTELOPE_ARCHIVE_SHA256 = (
    "8e182f14fc6e80b3bfa375b33eb6cff7ee05d8ef7633e738d1c89021dcf0c5c5"
)
ANTELOPE_MODEL_SHA256 = (
    "4ab1d6435d639628a6f3e5008dd4f929edf4c4124b1a7169e1048f9fef534cdf"
)
ANTELOPE_MODEL_BYTES = 260665334


def request(url):
    return urllib.request.Request(url, headers={"User-Agent": "FacesGallery-model-fetch/1"})


def get(url):
    with urllib.request.urlopen(request(url), timeout=300) as response:
        return response.read()


def download(url, destination):
    with urllib.request.urlopen(request(url), timeout=300) as response:
        with destination.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            result.update(chunk)
    return result.hexdigest()


def fetch_yunet():
    folder = "face_detection_yunet"
    filename = "face_detection_yunet_2023mar.onnx"
    relative = f"models/{folder}/{filename}"
    base = f"https://raw.githubusercontent.com/opencv/opencv_zoo/{OPENCV_REV}/"
    pointer = get(base + relative).decode()
    expected_sha = next(
        line.split("sha256:")[1]
        for line in pointer.splitlines()
        if line.startswith("oid sha256:")
    )
    expected_size = int(
        next(line.split()[1] for line in pointer.splitlines() if line.startswith("size "))
    )
    destination = ASSETS / "yunet.onnx"
    if not (
        destination.exists()
        and destination.stat().st_size == expected_size
        and digest(destination) == expected_sha
    ):
        temporary = destination.with_suffix(".onnx.tmp")
        download(
            f"https://media.githubusercontent.com/media/opencv/opencv_zoo/"
            f"{OPENCV_REV}/{relative}",
            temporary,
        )
        if temporary.stat().st_size != expected_size or digest(temporary) != expected_sha:
            temporary.unlink(missing_ok=True)
            raise RuntimeError("YuNet checksum/size mismatch")
        os.replace(temporary, destination)
    (ASSETS / "YUNET_LICENSE.txt").write_bytes(
        get(base + f"models/{folder}/LICENSE")
    )
    print(f"Verified yunet.onnx: {expected_size:,} bytes")
    return {
        "file": "yunet.onnx",
        "upstream": relative,
        "revision": OPENCV_REV,
        "sha256": expected_sha,
        "bytes": expected_size,
    }


def fetch_antelope_recognizer():
    destination = ASSETS / "insightface_glintr100.onnx"
    if not (
        destination.exists()
        and destination.stat().st_size == ANTELOPE_MODEL_BYTES
        and digest(destination) == ANTELOPE_MODEL_SHA256
    ):
        with tempfile.TemporaryDirectory(prefix="faces-antelope-") as temporary_dir:
            archive_path = Path(temporary_dir) / "antelopev2.zip"
            download(ANTELOPE_URL, archive_path)
            if digest(archive_path) != ANTELOPE_ARCHIVE_SHA256:
                raise RuntimeError("InsightFace antelopev2 archive checksum mismatch")
            with zipfile.ZipFile(archive_path) as archive:
                matches = [
                    item
                    for item in archive.infolist()
                    if Path(item.filename).name == "glintr100.onnx" and not item.is_dir()
                ]
                if len(matches) != 1:
                    raise RuntimeError("antelopev2 archive has an unexpected layout")
                temporary_model = destination.with_suffix(".onnx.tmp")
                with archive.open(matches[0]) as source, temporary_model.open("wb") as output:
                    shutil.copyfileobj(source, output, length=1024 * 1024)
                if (
                    temporary_model.stat().st_size != ANTELOPE_MODEL_BYTES
                    or digest(temporary_model) != ANTELOPE_MODEL_SHA256
                ):
                    temporary_model.unlink(missing_ok=True)
                    raise RuntimeError("InsightFace glintr100 checksum/size mismatch")
                os.replace(temporary_model, destination)
    print(f"Verified insightface_glintr100.onnx: {ANTELOPE_MODEL_BYTES:,} bytes")
    return {
        "file": "insightface_glintr100.onnx",
        "upstream": ANTELOPE_URL + "#antelopev2/glintr100.onnx",
        "release": "model-zoo",
        "package_sha256": ANTELOPE_ARCHIVE_SHA256,
        "sha256": ANTELOPE_MODEL_SHA256,
        "bytes": ANTELOPE_MODEL_BYTES,
        "usage": "non-commercial research only",
    }


def main():
    ASSETS.mkdir(parents=True, exist_ok=True)
    manifest = [fetch_yunet(), fetch_antelope_recognizer()]
    (ASSETS / "models.json").write_text(json.dumps(manifest, indent=2) + "\n")


if __name__ == "__main__":
    main()
