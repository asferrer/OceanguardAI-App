"""push_model_card.py — sube docs/submission/hf_model_card.md como README.md a HF.

Usa el mismo token cacheado que auto_promote_best.py para que sea idempotente.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import wmi_bypass  # noqa: F401

REPO_ID = os.environ.get("HF_REPO_ID", "asferrer/gemma-4-E2B-it-oceanguard-marine-debris")
MODEL_CARD = Path(__file__).resolve().parent.parent / "docs" / "submission" / "hf_model_card.md"


def get_token() -> str | None:
    tok = os.environ.get("HF_TOKEN") or os.environ.get("HUGGINGFACE_HUB_TOKEN")
    if tok:
        return tok
    cache = Path.home() / ".cache" / "huggingface" / "token"
    if cache.exists():
        return cache.read_text(encoding="utf-8").strip()
    return None


def main() -> int:
    if not MODEL_CARD.exists():
        print(f"ERROR: model card not found at {MODEL_CARD}")
        return 1

    token = get_token()
    if not token:
        print("ERROR: no HF token (env HF_TOKEN nor cached ~/.cache/huggingface/token)")
        return 1

    try:
        from huggingface_hub import HfApi
    except Exception as e:
        print(f"ERROR: huggingface_hub import failed: {e}")
        return 1

    api = HfApi(token=token)
    print(f"Uploading {MODEL_CARD.name} -> {REPO_ID}/README.md")
    try:
        api.upload_file(
            path_or_fileobj=str(MODEL_CARD),
            path_in_repo="README.md",
            repo_id=REPO_ID,
            repo_type="model",
            commit_message="Update model card: GGUF Q4_K_M available, deployment table updated, usage instructions added",
            token=token,
        )
        print(f"OK: pushed to https://huggingface.co/{REPO_ID}")
        return 0
    except Exception as e:
        print(f"ERROR uploading: {type(e).__name__}: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
