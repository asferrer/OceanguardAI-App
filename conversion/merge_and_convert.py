"""
OceanGuard AI - Plan B: Merge LoRA adapter into base model.

Merges the exp12_vision_lora LoRA adapter (rank=16, alpha=32, language + vision
encoder) into Gemma 4 E2B and saves the merged weights as a standalone
HuggingFace model directory. The merged directory can then be converted to
.litertlm with convert_base_model.py.

Usage:
    python conversion/merge_and_convert.py \\
        --adapter-path finetune/outputs/grid_best/adapter \\
        --base-model-id google/gemma-4-E2B-it \\
        --output-dir conversion/outputs/gemma-4-E2B-it-oceanguard-merged

    # Then convert the merged model:
    cd conversion/
    python convert_base_model.py --model-path outputs/gemma-4-E2B-it-oceanguard-merged

Requirements (install if missing):
    pip install peft transformers torch accelerate
"""

from __future__ import annotations

import argparse
import logging
import sys
import time
from pathlib import Path

import torch
from peft import PeftModel
from transformers import AutoModelForCausalLM, AutoTokenizer

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _sizeof_fmt(num_bytes: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if num_bytes < 1024.0:
            return f"{num_bytes:.1f} {unit}"
        num_bytes //= 1024
    return f"{num_bytes:.1f} TB"


def _dir_size(path: Path) -> int:
    return sum(f.stat().st_size for f in path.rglob("*") if f.is_file())


# ---------------------------------------------------------------------------
# Core logic
# ---------------------------------------------------------------------------


def merge_adapter(
    adapter_path: Path,
    base_model_id: str,
    output_dir: Path,
    dtype: str = "float16",
) -> None:
    """Load base + adapter, merge weights, save merged model to output_dir."""
    torch_dtype = getattr(torch, dtype)

    logger.info("=" * 60)
    logger.info("OceanGuard AI — LoRA Merge (Plan B)")
    logger.info("=" * 60)
    logger.info(f"Base model  : {base_model_id}")
    logger.info(f"Adapter     : {adapter_path}")
    logger.info(f"Output dir  : {output_dir}")
    logger.info(f"Dtype       : {dtype}")

    # Validate adapter directory
    adapter_weights = adapter_path / "adapter_model.safetensors"
    adapter_config = adapter_path / "adapter_config.json"
    if not adapter_weights.exists():
        raise FileNotFoundError(f"adapter_model.safetensors not found in {adapter_path}")
    if not adapter_config.exists():
        raise FileNotFoundError(f"adapter_config.json not found in {adapter_path}")

    adapter_size = _sizeof_fmt(adapter_weights.stat().st_size)
    logger.info(f"Adapter weights: {adapter_size}")

    # --- Step 1: Load base model ---
    logger.info("\n[1/4] Loading base model from HF (this downloads ~5 GB on first run)...")
    t0 = time.perf_counter()
    base_model = AutoModelForCausalLM.from_pretrained(
        base_model_id,
        torch_dtype=torch_dtype,
        device_map="auto",
        low_cpu_mem_usage=True,
    )
    logger.info(f"      Base model loaded in {time.perf_counter() - t0:.1f}s")

    # --- Step 2: Load tokenizer ---
    logger.info("[2/4] Loading tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(base_model_id)

    # --- Step 3: Apply LoRA adapter and merge ---
    logger.info("[3/4] Applying LoRA adapter...")
    t1 = time.perf_counter()
    peft_model = PeftModel.from_pretrained(
        base_model,
        str(adapter_path),
        torch_dtype=torch_dtype,
    )
    logger.info(f"      Adapter applied in {time.perf_counter() - t1:.1f}s")

    logger.info("      Merging weights (merge_and_unload)...")
    t2 = time.perf_counter()
    merged_model = peft_model.merge_and_unload()
    logger.info(f"      Merge completed in {time.perf_counter() - t2:.1f}s")

    # --- Step 4: Save merged model ---
    logger.info(f"[4/4] Saving merged model to {output_dir} ...")
    output_dir.mkdir(parents=True, exist_ok=True)
    t3 = time.perf_counter()
    merged_model.save_pretrained(str(output_dir), safe_serialization=True)
    tokenizer.save_pretrained(str(output_dir))
    elapsed_save = time.perf_counter() - t3

    saved_size = _sizeof_fmt(_dir_size(output_dir))
    logger.info(f"      Saved in {elapsed_save:.1f}s — directory size: {saved_size}")

    total_elapsed = time.perf_counter() - t0
    logger.info("\n" + "=" * 60)
    logger.info("MERGE COMPLETE")
    logger.info(f"Total time  : {total_elapsed / 60:.1f} min")
    logger.info(f"Output dir  : {output_dir}")
    logger.info("=" * 60)
    logger.info("\nNext step — convert merged model to .litertlm:")
    logger.info(f"    cd conversion/")
    logger.info(
        f"    python convert_base_model.py --model-path {output_dir.resolve()}"
    )
    logger.info(
        "    # Expected output: outputs/gemma-4-E2B-it-oceanguard.litertlm (~2.6 GB)"
    )
    logger.info(
        "    # Expected time  : 2-4h with GPU, 8-12h CPU-only"
    )


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Merge Gemma 4 E2B + exp10 LoRA adapter (Plan B for LiteRT conversion)",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--adapter-path",
        type=Path,
        default=Path("finetune/outputs/grid_best/adapter"),
        help="Directory containing adapter_model.safetensors and adapter_config.json",
    )
    parser.add_argument(
        "--base-model-id",
        type=str,
        default="google/gemma-4-E2B-it",
        help="HuggingFace model ID for Gemma 4 E2B base (needs HF token if gated)",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("conversion/outputs/gemma-4-E2B-it-oceanguard-merged"),
        help="Directory to save merged HuggingFace model",
    )
    parser.add_argument(
        "--dtype",
        choices=["float16", "bfloat16", "float32"],
        default="float16",
        help="Torch dtype for loading and merging weights",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)

    try:
        merge_adapter(
            adapter_path=args.adapter_path,
            base_model_id=args.base_model_id,
            output_dir=args.output_dir,
            dtype=args.dtype,
        )
        return 0
    except FileNotFoundError as exc:
        logger.error(f"File not found: {exc}")
        return 2
    except KeyboardInterrupt:
        logger.info("Interrupted by user")
        return 1
    except Exception as exc:
        logger.exception(f"Merge failed: {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
