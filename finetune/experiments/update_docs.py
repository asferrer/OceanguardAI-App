"""update_docs.py — patch WRITEUP §9 + notebook + KAGGLE form con resultados del grid.

- WRITEUP_FINAL.md: reemplaza la tabla "TBD pending …" en §9 por una tabla 10-fila
  (base + 9 exps) y añade "Honest disclosure" si delta < +0.20.
- notebook_finetune.ipynb: si existe una celda con marcador "HF_ADAPTER_URL",
  reemplaza por la URL real (o ruta del bundle si push falló).
- KAGGLE_SUBMISSION_FORM.md: deja URL HF consistente.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from config import CLASS_NAMES, EXP_OUTPUTS_BASE, REPO_ROOT, RESULTS_DIR  # noqa: E402

PROJECT_ROOT = REPO_ROOT.parent
WRITEUP = PROJECT_ROOT / "docs/submission/WRITEUP_FINAL.md"
KAGGLE = PROJECT_ROOT / "docs/submission/KAGGLE_SUBMISSION_FORM.md"
NOTEBOOK = PROJECT_ROOT / "docs/submission/notebook_finetune.ipynb"

HF_DEFAULT = "https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris"


def load_json(p: Path) -> dict | None:
    if not p.exists():
        return None
    with p.open(encoding="utf-8") as f:
        return json.load(f)


def build_results_block(rows: list[dict], base_map50: float, best_key: str | None) -> str:
    out = ["### Results — Grid de 9 Experimentos (TEST REAL hold-out 1000 imgs)", ""]
    out.append("Métrica oficial sobre TEST hold-out estratificado de 1000 imágenes "
               "no vistas durante el entrenamiento. Adapter elegido: **"
               f"{best_key or '—'}**.")
    out.append("")
    out.append("| key | mAP@0.5 | mAP@0.5:0.95 | JSON-validity | n_preds | mean latency (s) |")
    out.append("|---|---|---|---|---|---|")
    for r in rows:
        if r.get("status") != "ok":
            out.append(f"| {r['key']} | — | — | — | — | — |")
            continue
        out.append(
            f"| {r['key']} | {r['mAP_50']:.4f} | {r['mAP_50_95']:.4f} | "
            f"{r['json_validity']:.3f} | {r['n_predictions']} | {r['mean_latency_s']:.2f} |"
        )

    out.append("")
    out.append("**Per-class mAP@0.5 (todas las filas)**:")
    out.append("")
    out.append("| key | " + " | ".join(CLASS_NAMES) + " |")
    out.append("|---" + "|---" * len(CLASS_NAMES) + "|")
    for r in rows:
        if r.get("status") != "ok":
            continue
        cells = [f"{r.get(f'AP50_{n}', 0.0):.3f}" for n in CLASS_NAMES]
        out.append(f"| {r['key']} | " + " | ".join(cells) + " |")
    out.append("")
    delta = (next((r["mAP_50"] for r in rows if r["key"] == best_key), 0.0) - base_map50) if best_key else 0.0
    out.append(f"Δ mAP@0.5 best vs base = **{delta:+.4f}** "
               f"(base={base_map50:.4f}, best={best_key or '—'}).")
    if delta < 0.20:
        out.append("")
        out.append("### Honest disclosure")
        out.append("")
        out.append(
            f"El mejor adaptador alcanza Δ={delta:+.4f} mAP@0.5 respecto a la base, "
            "por debajo del umbral interno de +0.20 que nos habíamos fijado. "
            "Reportamos la cifra sin maquillaje: dejamos publicado el adaptador junto al "
            "grid completo (CSV/MD reproducibles en `finetune/experiments/results/`) para "
            "que cualquier evaluador pueda confirmar el resultado y comparar configuraciones."
        )
    return "\n".join(out) + "\n"


def patch_writeup(rows: list[dict], best_key: str | None, base_map50: float) -> bool:
    if not WRITEUP.exists():
        print(f"[update_docs] WRITEUP not found: {WRITEUP}")
        return False
    txt = WRITEUP.read_text(encoding="utf-8")
    block = build_results_block(rows, base_map50, best_key)

    start_marker = "### Results"
    next_section_marker = "### Deployment status"
    s = txt.find(start_marker, txt.find("## 9. Domain-Adapted Variant"))
    e = txt.find(next_section_marker, s) if s >= 0 else -1
    if s < 0 or e < 0:
        print("[update_docs] Markers no encontrados, append al final de §9")
        # fallback: append antes de §10
        anchor = txt.find("## 10. Reproducibility")
        if anchor < 0:
            print("[update_docs] §10 no encontrado, append al final")
            new_txt = txt.rstrip() + "\n\n" + block
        else:
            new_txt = txt[:anchor] + block + "\n" + txt[anchor:]
    else:
        new_txt = txt[:s] + block + "\n" + txt[e:]
    WRITEUP.write_text(new_txt, encoding="utf-8")
    print(f"[update_docs] WRITEUP patched: {WRITEUP}")
    return True


def patch_notebook(hf_url: str) -> bool:
    if not NOTEBOOK.exists():
        print(f"[update_docs] notebook not found: {NOTEBOOK}")
        return False
    with NOTEBOOK.open(encoding="utf-8") as f:
        nb = json.load(f)
    changed = False
    for cell in nb.get("cells", []):
        src = "".join(cell.get("source", []))
        if "HF_ADAPTER_URL" in src or "HF_ADAPTER_URL_PLACEHOLDER" in src or "huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris" in src:
            new_src = src.replace("HF_ADAPTER_URL_PLACEHOLDER", hf_url).replace(
                "HF_ADAPTER_URL", hf_url
            )
            # también acepta replace cuando se reemplaza por sí mismo
            cell["source"] = [line + "\n" for line in new_src.splitlines()]
            if cell["source"]:
                cell["source"][-1] = cell["source"][-1].rstrip("\n")
            changed = True
    if changed:
        with NOTEBOOK.open("w", encoding="utf-8") as f:
            json.dump(nb, f, indent=1, ensure_ascii=False)
        print(f"[update_docs] notebook patched: {NOTEBOOK}")
    else:
        print("[update_docs] notebook sin marcadores a sustituir (no-op)")
    return True


def ensure_kaggle_url(hf_url: str) -> None:
    if not KAGGLE.exists():
        print(f"[update_docs] KAGGLE not found: {KAGGLE}")
        return
    txt = KAGGLE.read_text(encoding="utf-8")
    if hf_url not in txt:
        txt = txt.replace(HF_DEFAULT, hf_url)
        KAGGLE.write_text(txt, encoding="utf-8")
        print(f"[update_docs] KAGGLE actualizada con {hf_url}")
    else:
        print("[update_docs] KAGGLE ya contiene la URL HF (no-op)")


def main() -> int:
    # leer results.csv para construir rows (sin re-leer json individuales)
    import csv
    csv_path = RESULTS_DIR / "results.csv"
    if not csv_path.exists():
        print(f"[update_docs] {csv_path} no encontrado")
        return 1
    rows: list[dict] = []
    with csv_path.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            cast: dict = {}
            for k, v in r.items():
                if v in ("", None):
                    cast[k] = ""
                    continue
                try:
                    cast[k] = float(v) if any(s in k for s in ("mAP", "AP50_", "json_validity", "latency", "elapsed")) else (int(v) if v.isdigit() else v)
                except Exception:
                    cast[k] = v
            rows.append(cast)

    best = load_json(RESULTS_DIR / "best.json") or {}
    best_key = best.get("best_key")
    base_map50 = float(best.get("base_mAP_50", 0.0))

    # determinar URL final: si promote_result.json indica push exitoso → usa url; si no → fallback default
    promote = load_json(EXP_OUTPUTS_BASE / "grid_best" / "promote_result.json") or {}
    hf_url = promote.get("url") or HF_DEFAULT

    patch_writeup(rows, best_key, base_map50)
    patch_notebook(hf_url)
    ensure_kaggle_url(hf_url)
    return 0


if __name__ == "__main__":
    sys.exit(main())
