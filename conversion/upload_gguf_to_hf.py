"""
OceanGuard AI — Upload GGUF Q4_K_M to HuggingFace.

Uploads the quantized GGUF file to the OceanGuard model repo alongside
the existing LoRA adapter artefacts. Uses the cached HF token.

Usage:
    python conversion/upload_gguf_to_hf.py

Or with explicit path:
    python conversion/upload_gguf_to_hf.py \
        --gguf conversion/outputs/gemma-4-E2B-it-oceanguard-Q4_K_M.gguf
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

# WMI bypass for Windows (safe no-op on Linux/Docker)
try:
    _wmi_bypass = Path(__file__).resolve().parent.parent / "finetune" / "wmi_bypass.py"
    if _wmi_bypass.exists():
        exec(_wmi_bypass.read_text())
except Exception:
    pass

REPO_ID = "asferrer/gemma-4-E2B-it-oceanguard-marine-debris"
DEFAULT_GGUF = Path(__file__).resolve().parent / "outputs" / "gemma-4-E2B-it-oceanguard-Q4_K_M.gguf"
REPO_FILENAME = "gemma-4-E2B-it-oceanguard-Q4_K_M.gguf"


def get_token() -> str | None:
    tok = os.environ.get("HF_TOKEN") or os.environ.get("HUGGINGFACE_HUB_TOKEN")
    if tok:
        return tok
    cache = Path.home() / ".cache" / "huggingface" / "token"
    if cache.exists():
        return cache.read_text(encoding="utf-8").strip()
    return None


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Upload OceanGuard GGUF Q4_K_M to HuggingFace",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--gguf",
        type=Path,
        default=DEFAULT_GGUF,
        help="Path to the Q4_K_M GGUF file to upload",
    )
    parser.add_argument(
        "--repo-id",
        type=str,
        default=REPO_ID,
        help="HuggingFace repo ID",
    )
    parser.add_argument(
        "--repo-filename",
        type=str,
        default=REPO_FILENAME,
        help="Filename to use inside the HF repo",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)

    if not args.gguf.exists():
        print(f"ERROR: GGUF file not found at {args.gguf}")
        print("Run Steps 3-4 (GGUF conversion + quantize) first.")
        return 1

    gguf_size_gb = args.gguf.stat().st_size / 1e9
    print(f"GGUF file : {args.gguf}")
    print(f"Size      : {gguf_size_gb:.2f} GB")
    print(f"Repo      : {args.repo_id}/{args.repo_filename}")

    token = get_token()
    if not token:
        print("ERROR: no HF token (set HF_TOKEN env or run huggingface-cli login)")
        return 1

    try:
        from huggingface_hub import HfApi
    except ImportError as e:
        print(f"ERROR: huggingface_hub import failed: {e}")
        return 1

    api = HfApi(token=token)
    print(f"Uploading {args.gguf.name} to {args.repo_id} ...")
    try:
        url = api.upload_file(
            path_or_fileobj=str(args.gguf),
            path_in_repo=args.repo_filename,
            repo_id=args.repo_id,
            repo_type="model",
            commit_message=(
                "Add GGUF Q4_K_M build of exp12_vision_lora (merged LoRA + quantized, "
                "mAP@0.5=0.3256 +205% vs base, llama.cpp compatible)"
            ),
            token=token,
        )
        print(f"OK: uploaded to {url}")
        print(f"Direct URL: https://huggingface.co/{args.repo_id}/resolve/main/{args.repo_filename}")
        return 0
    except Exception as e:
        print(f"ERROR uploading: {type(e).__name__}: {e}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
