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

"""SigLIP2 image encoder for Gemma4.

The SigLIP2 vision encoder in Gemma4 is architecturally IDENTICAL to Gemma3.
This file re-exports from the gemma3 image_encoder with Gemma4-appropriate
configuration (same image size 896x896, same 27 layers, same 256 token output).

The tensor names are the same prefix structure as Gemma3.

No changes are needed from the Gemma3 image encoder for Gemma4.
"""

# Direct re-export from gemma3 — no changes needed for Gemma4.
from ai_edge_torch.generative.examples.gemma3.image_encoder import (
    TENSOR_NAMES,
    SiglipExit,
    SiglipVisionEncoderWithExit,
    build_image_encoder,
    get_fake_image_encoder_config,
    get_image_encoder_config,
)

__all__ = [
    "TENSOR_NAMES",
    "SiglipExit",
    "SiglipVisionEncoderWithExit",
    "build_image_encoder",
    "get_fake_image_encoder_config",
    "get_image_encoder_config",
]
