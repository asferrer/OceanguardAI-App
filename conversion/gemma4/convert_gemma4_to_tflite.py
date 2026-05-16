# Copyright 2025 OceanGuard AI Project.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# ==============================================================================

"""Convert Gemma4 / Gemma3n model to TFLite (NOT .litertlm).

RESEARCH PROTOTYPE - outputs .tflite, NOT .litertlm.
See REPORT.md for the full blocker analysis.

KNOWN LIMITATIONS:
  - AltUp is simplified (single-stream). Semantics differ from trained model.
  - PLE (per-layer embeddings) is not loaded. Degrades quality.
  - Shared KV cache is ignored. Semantics differ for last 15 layers.
  - Output is .tflite, not .litertlm. Cannot be used with MediaPipe LlmInference.

SETUP (inside Docker container):
  1. Fix execstack:
     apt-get install -y patchelf
     patchelf --clear-execstack /usr/local/lib/python3.11/site-packages/ai_edge_litert/_pywrap_tensorflow_interpreter_wrapper.so

  2. Fix jaxlib version:
     pip install 'jaxlib==0.4.36' 'jax==0.4.36'

Usage:
  python convert_gemma4_to_tflite.py \\
    --checkpoint_path /path/to/gemma-3n-E2B-or-gemma-4-E2B-hf-checkpoint \\
    --output_path /tmp/ \\
    --quantize

The converter adds the gemma4/ directory to sys.path so it can be run from
any location inside the Docker container using the workspace mount.
"""

import os
import pathlib
import sys

# Allow running as: python /workspace/conversion/gemma4/convert_gemma4_to_tflite.py
_THIS_DIR = pathlib.Path(__file__).parent
_CONVERSION_DIR = _THIS_DIR.parent
sys.path.insert(0, str(_CONVERSION_DIR))

from absl import app
from absl import flags

# These imports require ai_edge_torch to be importable (run setup steps first)
from ai_edge_torch.generative.examples.gemma4 import decoder
from ai_edge_torch.generative.layers.experimental import kv_cache
from ai_edge_torch.generative.utilities import converter
from ai_edge_torch.generative.utilities.model_builder import ExportConfig
import torch


_CHECKPOINT_PATH = flags.DEFINE_string(
    "checkpoint_path",
    None,
    "Path to the HF checkpoint directory (merged model with safetensors).",
    required=True,
)
_OUTPUT_PATH = flags.DEFINE_string(
    "output_path",
    "/tmp/",
    "Directory to write the .tflite output.",
)
_OUTPUT_NAME_PREFIX = flags.DEFINE_string(
    "output_name_prefix",
    "gemma4_e2b",
    "Prefix for the output filename.",
)
_PREFILL_SEQ_LENS = flags.DEFINE_multi_integer(
    "prefill_seq_lens",
    (32, 64, 128, 256, 512, 1024),
    "Prefill sequence lengths. Each produces a separate tflite signature.",
)
_KV_CACHE_MAX_LEN = flags.DEFINE_integer(
    "kv_cache_max_len",
    2048,
    "Maximum KV cache size (prefill + decode).",
)
_QUANTIZE = flags.DEFINE_bool(
    "quantize",
    True,
    "Whether to apply INT8 dynamic quantization.",
)


def _create_causal_mask(seq_len: int, kv_cache_max_len: int) -> torch.Tensor:
    mask = torch.full((seq_len, kv_cache_max_len), float("-inf"), dtype=torch.float32)
    return torch.triu(mask, diagonal=1).unsqueeze(0).unsqueeze(0)


def _create_export_config(
    prefill_seq_lens: list,
    kv_cache_max_len: int,
) -> ExportConfig:
    export_config = ExportConfig()
    export_config.prefill_mask = [
        _create_causal_mask(seq_len, kv_cache_max_len)
        for seq_len in prefill_seq_lens
    ]
    export_config.decode_mask = _create_causal_mask(1, kv_cache_max_len)
    export_config.kvcache_cls = kv_cache.KVCacheTransposed
    return export_config


def main(_):
    print(f"Loading Gemma4 E2B from: {_CHECKPOINT_PATH.value}")
    print(f"KV cache max len: {_KV_CACHE_MAX_LEN.value}")
    print(f"Prefill seq lens: {_PREFILL_SEQ_LENS.value}")
    print(f"Quantize: {_QUANTIZE.value}")
    print()
    print("WARNING: This is a research prototype with known quality limitations.")
    print("  - AltUp simplified to single-stream (quality severely degraded)")
    print("  - PLE not loaded (quality degraded)")
    print("  - Shared KV cache not implemented (semantics wrong for last 15 layers)")
    print("  - Output is .tflite, NOT .litertlm (cannot use with MediaPipe)")
    print()

    pytorch_model = decoder.build_model_e2b(
        _CHECKPOINT_PATH.value,
        kv_cache_max_len=_KV_CACHE_MAX_LEN.value,
    )
    config = pytorch_model.config
    print(f"Model loaded. Layers: {config.num_layers}, Embedding: {config.embedding_dim}")

    export_config = _create_export_config(
        list(_PREFILL_SEQ_LENS.value),
        _KV_CACHE_MAX_LEN.value,
    )

    print("Starting conversion...")
    converter.convert_to_tflite(
        pytorch_model,
        output_path=_OUTPUT_PATH.value,
        output_name_prefix=_OUTPUT_NAME_PREFIX.value,
        prefill_seq_len=list(_PREFILL_SEQ_LENS.value),
        quantize=_QUANTIZE.value,
        config=config,
        export_config=export_config,
    )

    # Print output file info
    suffix = "q8" if _QUANTIZE.value else "f32"
    kv_size = _KV_CACHE_MAX_LEN.value
    output_file = os.path.join(
        _OUTPUT_PATH.value,
        f"{_OUTPUT_NAME_PREFIX.value}_{suffix}_ekv{kv_size}.tflite",
    )
    if os.path.exists(output_file):
        size_mb = os.path.getsize(output_file) / (1024 * 1024)
        print(f"SUCCESS: {output_file} ({size_mb:.1f} MB)")
    else:
        print(f"Conversion may have completed. Check {_OUTPUT_PATH.value}")


if __name__ == "__main__":
    app.run(main)
