"""
OceanGuard AI — LoRA merge script for Docker execution.

Uses standard transformers 5.x + manual LoRA weight injection to merge the
exp12_vision_lora adapter (r=16, alpha=32) into google/gemma-4-E2B-it.

Manual merge bypasses PEFT entirely, which avoids the Gemma4ClippableLinear
compatibility issue (PEFT does not recognise this custom wrapper in the SigLIP2
vision encoder).  The merge formula is simply:
    W_merged = W_base + (lora_alpha / r) * (B @ A)

Patches adapter_config.json on the fly to remap the Unsloth-specific
'Gemma4ForConditionalGeneration' class name to the canonical transformers name.

Run inside gemma3n-unsloth container with isolated conda env (menv):
    /opt/conda/bin/conda run --no-capture-output -n menv \
        env HF_HOME=/hfcache \
        python3 /workspace/conversion/merge_and_convert_docker.py
"""
from __future__ import annotations

import json
import os
import time
from pathlib import Path

# Force HF_HOME to the Linux-mapped cache dir.
# Needed because conda run can inherit a stale HF_HOME pointing to a Windows
# path (set by the outer Unsloth container entrypoint) which causes
# PermissionError at C:.
if not os.environ.get("HF_HOME", "").startswith("/"):
    os.environ["HF_HOME"] = "/hfcache"

import torch
from safetensors.torch import load_file as load_safetensors
from transformers import AutoModelForCausalLM, AutoProcessor, AutoTokenizer

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
ADAPTER_PATH = Path("/workspace/finetune/outputs/grid_best/adapter")
OUTPUT_DIR = Path("/workspace/conversion/outputs/gemma-4-E2B-it-oceanguard-merged")
BASE_MODEL_ID = "google/gemma-4-E2B-it"


def load_adapter_config(adapter_path: Path) -> dict:
    """Load and return the adapter_config.json as a dict."""
    cfg_path = adapter_path / "adapter_config.json"
    cfg = json.loads(cfg_path.read_text())
    print(f"adapter r={cfg['r']}, alpha={cfg['lora_alpha']}, "
          f"targets={cfg['target_modules']}")
    return cfg


def load_lora_weights(adapter_path: Path) -> dict[str, torch.Tensor]:
    """
    Load all LoRA tensors from adapter_model.safetensors (or .bin fallback).
    Returns a flat dict keyed by tensor name.
    """
    st_file = adapter_path / "adapter_model.safetensors"
    if st_file.exists():
        print(f"Loading LoRA weights from {st_file.name}")
        return load_safetensors(str(st_file), device="cpu")

    bin_file = adapter_path / "adapter_model.bin"
    if bin_file.exists():
        print(f"Loading LoRA weights from {bin_file.name}")
        return torch.load(str(bin_file), map_location="cpu", weights_only=True)

    raise FileNotFoundError(
        f"No adapter_model.safetensors or adapter_model.bin found in {adapter_path}"
    )


def _get_param(model: torch.nn.Module, name: str) -> torch.nn.Parameter | None:
    """
    Retrieve a parameter by dotted name, handling Gemma4ClippableLinear which
    wraps its weight as  module.linear.weight  instead of  module.weight.
    """
    parts = name.split(".")
    obj = model
    for p in parts:
        obj = getattr(obj, p, None)
        if obj is None:
            return None
    return obj if isinstance(obj, torch.nn.Parameter) else None


def merge_lora_into_model(
    model: torch.nn.Module,
    lora_weights: dict[str, torch.Tensor],
    r: int,
    alpha: float,
) -> None:
    """
    Apply LoRA deltas directly onto the base model parameters in-place.

    PEFT stores tensors with keys like:
        base_model.model.<module_path>.lora_A.weight
        base_model.model.<module_path>.lora_B.weight

    We reconstruct the target module path, look up the corresponding base
    parameter (handling .linear.weight wrappers), and add the delta.
    """
    scale = alpha / r
    prefix = "base_model.model."

    # Collect all unique module paths that have both A and B.
    a_keys = {k for k in lora_weights if k.endswith(".lora_A.weight")}
    b_keys = {k for k in lora_weights if k.endswith(".lora_B.weight")}

    merged = 0
    skipped = 0

    for a_key in sorted(a_keys):
        # e.g. "base_model.model.model.layers.0.self_attn.q_proj.lora_A.weight"
        b_key = a_key.replace(".lora_A.weight", ".lora_B.weight")
        if b_key not in b_keys:
            print(f"  WARN: no matching lora_B for {a_key} — skipping")
            skipped += 1
            continue

        # Module path relative to the model root.
        # Strip "base_model.model." prefix and ".lora_A.weight" suffix.
        if a_key.startswith(prefix):
            mod_path = a_key[len(prefix):]  # e.g. "model.layers.0.self_attn.q_proj.lora_A.weight"
        else:
            mod_path = a_key
        mod_path = mod_path.replace(".lora_A.weight", "")  # e.g. "model.layers.0.self_attn.q_proj"

        lora_A = lora_weights[a_key].float()  # (r, in_features)
        lora_B = lora_weights[b_key].float()  # (out_features, r)
        delta = scale * (lora_B @ lora_A)     # (out_features, in_features)

        # Try <mod_path>.weight first (standard nn.Linear).
        param = _get_param(model, f"{mod_path}.weight")

        # Fallback: Gemma4ClippableLinear stores the real weight at .linear.weight
        if param is None:
            param = _get_param(model, f"{mod_path}.linear.weight")

        if param is None:
            print(f"  WARN: parameter not found for {mod_path} — skipping")
            skipped += 1
            continue

        with torch.no_grad():
            param.data.add_(delta.to(param.data.dtype).to(param.data.device))
        merged += 1

    print(f"  Merged {merged} LoRA pairs, skipped {skipped}")


def main() -> None:
    import transformers

    print("=" * 60)
    print("OceanGuard AI — LoRA Merge (Docker) [manual merge]")
    print(f"  transformers : {transformers.__version__}")
    print(f"  torch        : {torch.__version__}")
    cuda_ok = torch.cuda.is_available()
    print(f"  CUDA         : {torch.cuda.get_device_name(0) if cuda_ok else 'CPU-only'}")
    print(f"  adapter      : {ADAPTER_PATH}")
    print(f"  output       : {OUTPUT_DIR}")
    print("=" * 60)

    # --- adapter config ---
    cfg = load_adapter_config(ADAPTER_PATH)
    r = cfg["r"]
    alpha = float(cfg["lora_alpha"])

    # --- 1. Load base model ---
    print(f"\n[1/4] Loading {BASE_MODEL_ID} ...")
    t0 = time.perf_counter()
    base_model = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL_ID,
        dtype=torch.float16,
        device_map="auto",
        low_cpu_mem_usage=True,
    )
    print(f"      Loaded in {time.perf_counter() - t0:.1f}s  arch={type(base_model).__name__}")

    # --- 2. Tokenizer + processor ---
    print("[2/4] Loading tokenizer + processor ...")
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL_ID)
    has_proc = False
    try:
        processor = AutoProcessor.from_pretrained(BASE_MODEL_ID)
        has_proc = True
        print(f"      Processor: {type(processor).__name__}")
    except Exception as exc:
        print(f"      No processor (non-fatal): {exc}")

    # --- 3. Load LoRA weights and apply manually ---
    print("[3/4] Loading LoRA weights and merging ...")
    lora_weights = load_lora_weights(ADAPTER_PATH)
    print(f"      {len(lora_weights)} tensors loaded from adapter")
    t1 = time.perf_counter()
    merge_lora_into_model(base_model, lora_weights, r=r, alpha=alpha)
    print(f"      Merge done in {time.perf_counter() - t1:.1f}s")

    # Free adapter tensors
    del lora_weights
    torch.cuda.empty_cache() if cuda_ok else None

    # --- 4. Save ---
    print(f"[4/4] Saving to {OUTPUT_DIR} ...")
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    t3 = time.perf_counter()
    base_model.save_pretrained(str(OUTPUT_DIR), safe_serialization=True)
    tokenizer.save_pretrained(str(OUTPUT_DIR))
    if has_proc:
        processor.save_pretrained(str(OUTPUT_DIR))
    elapsed = time.perf_counter() - t3

    total = sum(f.stat().st_size for f in OUTPUT_DIR.rglob("*") if f.is_file())
    print(f"      Saved in {elapsed:.1f}s  {total / 1e9:.2f} GB")
    print("\n=== MERGE COMPLETE ===")
    print(f"Next: convert to GGUF f16, then quantize Q4_K_M")


if __name__ == "__main__":
    main()
