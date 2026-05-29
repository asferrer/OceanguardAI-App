"""build_merged_yaml.py — Une los datasets disponibles en un solo data.yaml YOLO.

Cada dataset queda en su carpeta (no se mueven archivos). Generamos
`splits/train.txt` y `splits/val.txt` con paths absolutos a las imágenes, y un
`marine_1class.yaml` que apunta a esos txt. Ultralytics admite este formato.

Reglas:
  - Cada dataset con sus splits originales:
      Brackish: images/{train,valid,test} → train+test=train, valid=val
      Aquarium: images/{train,valid,test} → train+test=train, valid=val
      DeepFish: imgs sueltos → 80/20 estratificado por habitat
      SUIM:     imgs sueltos (todos negativos) → 80/20 round-robin
  - Solo se incluyen imágenes que tienen su .txt correspondiente (idempotente
    si Ultralytics escaneó cache anterior con otra estructura).
  - Negativos = .txt vacíos; Ultralytics los acepta como background frames.

Uso:
  python build_merged_yaml.py
"""
from __future__ import annotations

import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATA = ROOT / "datasets"
SPLITS = ROOT / "splits"
SPLITS.mkdir(parents=True, exist_ok=True)

IMG_EXTS = {".jpg", ".jpeg", ".png"}
SEED = 42


def _pair(img_path: Path, labels_root: Path, img_root: Path) -> Path | None:
    """Encuentra el .txt correspondiente; None si no existe."""
    rel = img_path.relative_to(img_root)
    lbl = labels_root / rel.with_suffix(".txt")
    return lbl if lbl.exists() else None


def _list_with_labels(img_dir: Path, lbl_dir: Path) -> list[Path]:
    """Lista imágenes que tienen .txt asociado (incluye .txt vacíos = negativos)."""
    out: list[Path] = []
    if not img_dir.exists() or not lbl_dir.exists():
        return out
    for p in img_dir.rglob("*"):
        if p.suffix.lower() not in IMG_EXTS or not p.is_file():
            continue
        if _pair(p, lbl_dir, img_dir) is not None:
            out.append(p)
    return sorted(out)


def collect_brackish() -> tuple[list[Path], list[Path]]:
    root = DATA / "brackish"
    if not root.exists():
        return [], []
    img_root = root / "images"
    lbl_root = root / "labels"
    train = _list_with_labels(img_root / "train", lbl_root / "train")
    test = _list_with_labels(img_root / "test", lbl_root / "test")
    val = _list_with_labels(img_root / "valid", lbl_root / "valid")
    return (train + test, val)


def collect_aquarium() -> tuple[list[Path], list[Path]]:
    root = DATA / "aquarium"
    if not root.exists():
        return [], []
    img_root = root / "images"
    lbl_root = root / "labels"
    train = _list_with_labels(img_root / "train", lbl_root / "train")
    test = _list_with_labels(img_root / "test", lbl_root / "test")
    val = _list_with_labels(img_root / "valid", lbl_root / "valid")
    return (train + test, val)


def collect_deepfish() -> tuple[list[Path], list[Path]]:
    root = DATA / "deepfish"
    if not root.exists():
        return [], []
    imgs = _list_with_labels(root / "images", root / "labels")
    if not imgs:
        return [], []
    rng = random.Random(SEED)
    rng.shuffle(imgs)
    cut = int(len(imgs) * 0.8)
    return imgs[:cut], imgs[cut:]


def collect_suim() -> tuple[list[Path], list[Path]]:
    """All-negative split (1635 imgs, .txt empty). 80/20."""
    root = DATA / "suim"
    if not root.exists():
        return [], []
    imgs = _list_with_labels(root / "images", root / "labels")
    if not imgs:
        return [], []
    rng = random.Random(SEED + 1)
    rng.shuffle(imgs)
    cut = int(len(imgs) * 0.8)
    return imgs[:cut], imgs[cut:]


def _collect_split_dir(slug: str) -> tuple[list[Path], list[Path]]:
    """Generic collector for datasets shipped with images/{train,valid,test}/
    + labels/{train,valid,test}/, used by URPC, Peixos, and OpenImages_marine
    (the v3 domain-extension datasets)."""
    root = DATA / slug
    if not root.exists():
        return [], []
    img_root = root / "images"
    lbl_root = root / "labels"
    train = _list_with_labels(img_root / "train", lbl_root / "train")
    test = _list_with_labels(img_root / "test", lbl_root / "test")
    val = _list_with_labels(img_root / "valid", lbl_root / "valid")
    return (train + test, val)


def collect_urpc_reef() -> tuple[list[Path], list[Path]]:
    """URPC reef invertebrates (sea urchin/cucumber/scallop/starfish/algae)."""
    return _collect_split_dir("urpc_reef")


def collect_peixos() -> tuple[list[Path], list[Path]]:
    """Peixos — Mediterranean reef fish (Roboflow RF100)."""
    return _collect_split_dir("peixos")


def collect_openimages_marine() -> tuple[list[Path], list[Path]]:
    """OpenImages V6 marine subset — Flickr amateur-style photography."""
    return _collect_split_dir("openimages_marine")


def main() -> None:
    sources = {
        "brackish": collect_brackish(),
        "aquarium": collect_aquarium(),
        "deepfish": collect_deepfish(),
        "suim": collect_suim(),
        # v3 domain-extension (added 2026-05-29 to close the iNat-amateur gap)
        "urpc_reef": collect_urpc_reef(),
        "peixos": collect_peixos(),
        "openimages_marine": collect_openimages_marine(),
    }
    train_lines: list[str] = []
    val_lines: list[str] = []
    print(f"{'dataset':<10}  {'train':>7}  {'val':>5}")
    print("-" * 28)
    for name, (tr, va) in sources.items():
        print(f"{name:<10}  {len(tr):>7}  {len(va):>5}")
        train_lines.extend(str(p).replace("\\", "/") for p in tr)
        val_lines.extend(str(p).replace("\\", "/") for p in va)
    print("-" * 28)
    print(f"{'TOTAL':<10}  {len(train_lines):>7}  {len(val_lines):>5}")

    (SPLITS / "train.txt").write_text("\n".join(train_lines), encoding="utf-8")
    (SPLITS / "val.txt").write_text("\n".join(val_lines), encoding="utf-8")

    yaml_path = ROOT / "marine_1class.yaml"
    yaml_path.write_text(
        "# Merged marine 1-class detector dataset (auto-generated).\n"
        f"# Brackish + Aquarium + DeepFish + SUIM (negatives).\n"
        f"path: {ROOT.as_posix()}\n"
        f"train: splits/train.txt\n"
        f"val: splits/val.txt\n"
        "names:\n  0: organism\n",
        encoding="utf-8",
    )
    print(f"\nWrote {yaml_path.name} + splits/{{train,val}}.txt")


if __name__ == "__main__":
    main()
