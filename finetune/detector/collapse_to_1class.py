"""collapse_to_1class.py — Convierte labels YOLO multi-clase a una sola "organism".

Reescribe todos los .txt bajo `--labels-dir` (recursivo) sustituyendo el primer
campo (class id) por 0. Idempotente: si ya está colapsado no hace nada.

Uso:
  python collapse_to_1class.py --labels-dir datasets/aquarium/labels
  python collapse_to_1class.py --labels-dir datasets/brackish/labels --dry-run
"""
from __future__ import annotations

import argparse
from pathlib import Path


def collapse_file(path: Path, dry_run: bool = False) -> tuple[int, int]:
    """Devuelve (lines, changed)."""
    text = path.read_text(encoding="utf-8")
    out_lines: list[str] = []
    changed = 0
    for line in text.splitlines():
        line = line.strip()
        if not line:
            out_lines.append("")
            continue
        parts = line.split()
        if not parts:
            continue
        if parts[0] != "0":
            parts[0] = "0"
            changed += 1
        out_lines.append(" ".join(parts))
    new_text = "\n".join(out_lines)
    if not dry_run and changed > 0:
        path.write_text(new_text + ("\n" if text.endswith("\n") else ""),
                        encoding="utf-8")
    return len(out_lines), changed


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--labels-dir", type=Path, required=True)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if not args.labels_dir.exists():
        raise SystemExit(f"labels-dir no existe: {args.labels_dir}")

    total_files = 0
    total_lines = 0
    total_changed = 0
    for p in sorted(args.labels_dir.rglob("*.txt")):
        total_files += 1
        lines, changed = collapse_file(p, dry_run=args.dry_run)
        total_lines += lines
        total_changed += changed

    mode = "DRY-RUN" if args.dry_run else "REWROTE"
    print(f"[{mode}] files={total_files} lines={total_lines} changed={total_changed}")


if __name__ == "__main__":
    main()
