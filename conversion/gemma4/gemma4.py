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

"""Gemma4 multimodal model prototype for ai-edge-torch export.

RESEARCH PROTOTYPE - not production quality.

Architecture: Gemma3n E2B (= Gemma4 E2B proxy, architecturally equivalent).

Key differences from Gemma3:
  - AltUp (Alternating Updates): 4 parallel hidden-state streams
  - LAUREL: learned low-rank augmented residual
  - PLE: per-layer embeddings from a separate vocab
  - Shared KV cache (last 15 layers)
  - Activation sparsity (gaussian topk) in MLP
  - Larger model (2048 hidden, 35 layers, 8 heads, GQA 2 KV heads)

KNOWN LIMITATIONS of this prototype (see REPORT.md):
  1. Shared KV cache is NOT implemented - each layer gets its own cache slot.
     This means the last 15 layers will produce different KV than the trained
     model expects. Outputs will be incorrect.
  2. AltUp is simplified to single-stream (active idx=0 only). The 4 parallel
     streams collapse to 1. This is a significant approximation that severely
     degrades model quality.
  3. PLE is accepted as an input but can be zero-padded for testing.
  4. Output is .tflite, NOT .litertlm — cannot be loaded by MediaPipe LlmInference.
"""

from dataclasses import dataclass
from typing import List, Optional, Tuple

from ai_edge_torch.generative.examples.gemma4 import decoder
from ai_edge_torch.generative.examples.gemma4 import image_encoder
from ai_edge_torch.generative.layers import builder
from ai_edge_torch.generative.layers import kv_cache as kv_utils
import ai_edge_torch.generative.layers.model_config as cfg
from ai_edge_torch.generative.utilities import model_builder
import ai_edge_torch.generative.utilities.loader as loading_utils
import torch
from torch import nn


PROJECTION_TENSOR_NAME = "multi_modal_projector.linear"


@dataclass
class Gemma4MMConfig:
    """Gemma4 multimodal model configuration."""

    image_encoder_config: cfg.ModelConfig
    decoder_config: cfg.ModelConfig
    mm_norm_config: cfg.NormalizationConfig
    mm_extra_tokens: int
    image_token_id: int
    image_projection_scale: float
    image_projection_use_bias: bool = False


class Gemma4MM(nn.Module):
    """Gemma4 multimodal model (prototype).

    Wraps the decoder and SigLIP2 image encoder for ai-edge-torch export.
    The AltUp simplification means this is NOT equivalent to the trained model.
    """

    def __init__(self, config: Gemma4MMConfig):
        super().__init__()
        self.image_encoder = image_encoder.SiglipVisionEncoderWithExit(
            config.image_encoder_config
        )
        self.decoder = decoder.Decoder(config.decoder_config)
        self.mm_norm = builder.build_norm(
            config.image_encoder_config.embedding_dim,
            config.mm_norm_config,
        )
        self.extra_embedding = nn.Embedding(
            config.mm_extra_tokens, config.image_encoder_config.embedding_dim
        )
        self.image_projection = nn.Linear(
            config.image_encoder_config.embedding_dim,
            config.decoder_config.embedding_dim,
            bias=config.image_projection_use_bias,
        )
        image_embedding_config = config.image_encoder_config.image_embedding
        self.num_patches = (
            image_embedding_config.image_size // image_embedding_config.patch_size
        ) ** 2
        self.config = config

    @torch.inference_mode()
    def forward(
        self,
        tokens: torch.Tensor,
        input_pos: torch.Tensor,
        kv_cache: kv_utils.KVCache,
        image_indices: Optional[torch.Tensor] = None,
        image_feat_indices: Optional[torch.Tensor] = None,
        pixel_values: torch.Tensor = None,
        export_config: Optional[model_builder.ExportConfig] = None,
    ) -> dict:
        _, seq_len = tokens.size()
        assert self.config.decoder_config.max_seq_len >= seq_len

        if pixel_values is None:
            return self.decoder(
                tokens=tokens,
                input_pos=input_pos,
                kv_cache=kv_cache,
                input_embeds=None,
                export_config=export_config,
            )

        vocab_size = self.config.decoder_config.vocab_size
        input_embeds = self.decoder.tok_embedding(
            torch.clip(tokens, 0, vocab_size - 1)
        )
        if self.decoder.config.embedding_scale is not None:
            input_embeds = input_embeds * self.decoder.config.embedding_scale

        batch_size, num_media, c, h, w = pixel_values.size()
        pixel_values = pixel_values.view(-1, c, h, w)
        image_encoded = self.image_encoder(pixel_values=pixel_values)
        image_encoded = self.mm_norm(image_encoded)
        image_encoded = self.image_projection(image_encoded)
        _, num_patches, num_channels = image_encoded.size()
        image_encoded = image_encoded.view(
            batch_size, num_media, num_patches, num_channels
        )

        for b in range(tokens.shape[0]):
            unbatched = image_encoded[b]
            image_features = unbatched[image_indices[b], image_feat_indices[b]]
            index_to_copy = torch.where(image_indices[b] >= 0)[0]
            input_embeds[b] = torch.index_copy(
                input_embeds[b], 0, index_to_copy, image_features[index_to_copy]
            )

        return self.decoder(
            tokens=None,
            input_pos=input_pos,
            kv_cache=kv_cache,
            input_embeds=input_embeds,
            image_indices=image_indices,
            export_config=export_config,
        )


def get_fake_model_config(**kwargs) -> Gemma4MMConfig:
    return Gemma4MMConfig(
        image_encoder_config=image_encoder.get_fake_image_encoder_config(),
        decoder_config=decoder.get_fake_decoder_config(**kwargs),
        image_token_id=262_144,
        image_projection_scale=256**0.5,
        image_projection_use_bias=False,
        mm_norm_config=cfg.NormalizationConfig(
            type=cfg.NormalizationType.LAYER_NORM,
            epsilon=1e-6,
            enable_hlfb=True,
        ),
        mm_extra_tokens=32,
    )


def build_model_e2b(checkpoint_path: str, **kwargs) -> decoder.Decoder:
    """Build Gemma4 E2B text-only decoder from HF checkpoint."""
    if checkpoint_path:
        model = decoder.build_model_e2b(checkpoint_path, **kwargs)
    else:
        config = decoder.get_decoder_config_e2b(**kwargs)
        model = decoder.Decoder(config)
    model.eval()
    return model
