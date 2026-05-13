"""auto_promote_best.py — copia best adapter + HF push + fallback bundle.

Lee experiments/results/best.json para identificar key ganadora.
Copia outputs/lora_<key>/adapter -> outputs/grid_best/adapter.
Push HF a asferrer/gemma-4-E2B-it-oceanguard-marine-debris (1 retry).
Si HF falla 2x, bundle .tar.gz local y log instrucciones manuales.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tarfile
import time
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from config import EXP_OUTPUTS_BASE, RESULTS_DIR  # noqa: E402

HF_REPO = os.environ.get("HF_REPO_ID", "asferrer/gemma-4-E2B-it-oceanguard-marine-debris")
GRID_BEST_DIR = EXP_OUTPUTS_BASE / "grid_best"


def load_best() -> dict:
    p = RESULTS_DIR / "best.json"
    if not p.exists():
        raise SystemExit(f"best.json missing: {p}")
    with p.open(encoding="utf-8") as f:
        return json.load(f)


def copy_adapter(src: Path, dst: Path) -> None:
    if dst.exists():
        shutil.rmtree(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(src, dst)
    print(f"[promote] copied {src} -> {dst}")


def hf_push(adapter_dir: Path, repo_id: str) -> tuple[bool, str]:
    token = os.environ.get("HF_TOKEN") or os.environ.get("HUGGINGFACE_HUB_TOKEN")
    if not token:
        # fallback to ~/.cache/huggingface/token (set by huggingface-cli login)
        tok_path = Path.home() / ".cache" / "huggingface" / "token"
        if tok_path.exists():
            token = tok_path.read_text(encoding="utf-8").strip()
    if not token:
        return False, "HF_TOKEN missing"
    try:
        from huggingface_hub import HfApi, create_repo
    except Exception as e:
        return False, f"huggingface_hub import failed: {e}"
    try:
        api = HfApi(token=token)
        create_repo(repo_id=repo_id, token=token, exist_ok=True, repo_type="model", private=False)
        api.upload_folder(
            folder_path=str(adapter_dir),
            repo_id=repo_id,
            repo_type="model",
            commit_message="Upload best LoRA adapter (auto_promote_best)",
            token=token,
        )
        return True, f"pushed to https://huggingface.co/{repo_id}"
    except Exception as e:
        return False, f"{type(e).__name__}: {e}\n{traceback.format_exc()}"


def bundle_tarball(adapter_dir: Path, dst_dir: Path) -> Path:
    dst_dir.mkdir(parents=True, exist_ok=True)
    out = dst_dir / "grid_best_adapter.tar.gz"
    if out.exists():
        out.unlink()
    with tarfile.open(out, "w:gz") as tf:
        tf.add(adapter_dir, arcname="adapter")
    return out


def main() -> int:
    best = load_best()
    key = best.get("best_key")
    if not key:
        print("[promote] no best key — nothing to do")
        return 0
    src = EXP_OUTPUTS_BASE / f"lora_{key}" / "adapter"
    if not src.exists():
        print(f"[promote] adapter missing for best={key}: {src}")
        return 1

    copy_adapter(src, GRID_BEST_DIR / "adapter")
    (GRID_BEST_DIR / "best_meta.json").write_text(json.dumps(best, indent=2, ensure_ascii=False), encoding="utf-8")

    result = {"best": best, "hf_repo": HF_REPO, "status": None, "detail": None,
              "bundle_path": None, "started_at": time.time()}

    print(f"[promote] HF push intento 1 -> {HF_REPO}")
    ok, info = hf_push(GRID_BEST_DIR / "adapter", HF_REPO)
    if not ok:
        print(f"[promote] HF push fallo 1: {info}")
        print("[promote] retry HF push…")
        time.sleep(10)
        ok, info = hf_push(GRID_BEST_DIR / "adapter", HF_REPO)
    if ok:
        result["status"] = "hf_pushed"
        result["detail"] = info
        result["url"] = f"https://huggingface.co/{HF_REPO}"
        print(f"[promote] OK: {info}")
    else:
        print(f"[promote] HF push falló 2x: {info}")
        bundle = bundle_tarball(GRID_BEST_DIR / "adapter", GRID_BEST_DIR)
        result["status"] = "bundle_only"
        result["detail"] = info
        result["bundle_path"] = str(bundle)
        print(f"[promote] bundle local: {bundle}")
        print("[promote] subida manual:")
        print(f"    huggingface-cli login")
        print(f"    huggingface-cli upload {HF_REPO} {GRID_BEST_DIR/'adapter'} . --repo-type model")

    (GRID_BEST_DIR / "promote_result.json").write_text(
        json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"[promote] result -> {GRID_BEST_DIR / 'promote_result.json'}")
    return 0 if result["status"] in ("hf_pushed", "bundle_only") else 1


if __name__ == "__main__":
    sys.exit(main())
