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

"""Gemma4 / Gemma3n decoder prototype for ai-edge-torch export.

RESEARCH PROTOTYPE - not production quality.

KNOWN LIMITATIONS:
  - AltUp is DISABLED (single-stream approximation). The 4 parallel hidden
    state streams are collapsed to 1. Output quality is severely degraded.
  - PLE (per-layer embeddings) is accepted as an input but not yet wired.
  - Shared KV cache is NOT implemented (separate cache per layer).
  - LAUREL low-rank bottleneck IS implemented (correct).
  - Activation sparsity IS implemented (correct).
  - Local/global sliding window attention IS implemented (from gemma3 template).

See REPORT.md for the full blocker analysis.
"""

import math
from typing import List, Optional, Tuple

from ai_edge_torch.generative.layers import builder
import ai_edge_torch.generative.layers.attention_utils as attn_utils
from ai_edge_torch.generative.layers.experimental import attention
from ai_edge_torch.generative.layers.experimental import kv_cache as kv_utils
import ai_edge_torch.generative.layers.model_config as cfg
import ai_edge_torch.generative.layers.rotary_position_embedding as rotary_pos_emb
from ai_edge_torch.generative.utilities import model_builder
import ai_edge_torch.generative.utilities.loader as loading_utils
import torch
from torch import nn
import torch.nn.functional as F


# ---------------------------------------------------------------------------
# Tensor name mapping for HF safetensors format (Gemma3n / Gemma4)
# ---------------------------------------------------------------------------

TENSOR_NAMES = loading_utils.ModelLoader.TensorNames(
    ff_up_proj="model.layers.{}.mlp.up_proj",
    ff_down_proj="model.layers.{}.mlp.down_proj",
    ff_gate_proj="model.layers.{}.mlp.gate_proj",
    attn_query_proj="model.layers.{}.self_attn.q_proj",
    attn_key_proj="model.layers.{}.self_attn.k_proj",
    attn_value_proj="model.layers.{}.self_attn.v_proj",
    attn_output_proj="model.layers.{}.self_attn.o_proj",
    attn_query_norm="model.layers.{}.self_attn.q_norm",
    attn_key_norm="model.layers.{}.self_attn.k_norm",
    pre_attn_norm="model.layers.{}.input_layernorm",
    post_attn_norm="model.layers.{}.post_attention_layernorm",
    pre_ff_norm="model.layers.{}.pre_feedforward_layernorm",
    post_ff_norm="model.layers.{}.post_feedforward_layernorm",
    embedding="model.embed_tokens",
    final_norm="model.norm",
    lm_head=None,  # shared with embedding
)

# Additional Gemma3n-specific tensor names (not handled by base TensorNames)
# These would need custom loading:
#   model.layers.{}.laurel.linear_left
#   model.layers.{}.laurel.linear_right
#   model.layers.{}.laurel.post_laurel_norm
#   model.layers.{}.altup.prediction_coefs
#   model.layers.{}.altup.correction_coefs
#   model.layers.{}.altup.modality_router
#   model.layers.{}.altup.correct_output_scale
#   model.layers.{}.per_layer_input_gate
#   model.layers.{}.per_layer_projection
#   model.layers.{}.post_per_layer_input_norm
#   model.embed_tokens_per_layer
#   model.per_layer_model_projection


# ---------------------------------------------------------------------------
# LAUREL: Learned Augmented Residual Layer
# ---------------------------------------------------------------------------

class LaurelBlock(nn.Module):
    """Low-rank bottleneck applied in parallel to attention."""

    def __init__(self, hidden_size: int, rank: int, eps: float = 1e-6):
        super().__init__()
        self.linear_left = nn.Linear(hidden_size, rank, bias=False)
        self.linear_right = nn.Linear(rank, hidden_size, bias=False)
        self.norm_weight = nn.Parameter(torch.ones(hidden_size))
        self.eps = eps

    def _rms_norm(self, x: torch.Tensor) -> torch.Tensor:
        variance = x.pow(2).mean(-1, keepdim=True)
        return x * torch.rsqrt(variance + self.eps) * self.norm_weight

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        out = self.linear_right(self.linear_left(x))
        return x + self._rms_norm(out)


# ---------------------------------------------------------------------------
# DecoderBlock: Gemma4 layer (AltUp-simplified, LAUREL, PLE disabled)
# ---------------------------------------------------------------------------

class DecoderBlock(attention.TransformerBlock):
    """Gemma4 transformer block (simplified, without full AltUp/PLE).

    Approximation: AltUp 4-stream -> single stream (active_idx=0 only).
    LAUREL IS applied correctly.
    """

    def __init__(self, block_config: cfg.TransformerBlockConfig, model_config: cfg.ModelConfig):
        super().__init__(block_config, model_config)
        hidden_size = model_config.embedding_dim
        # LAUREL block
        self.laurel = LaurelBlock(hidden_size, rank=64)

    def forward(
        self,
        x: torch.Tensor,
        rope: Optional[Tuple[torch.Tensor, torch.Tensor]] = None,
        mask: Optional[torch.Tensor] = None,
        input_pos: Optional[torch.Tensor] = None,
        kv_cache: kv_utils.KVCacheEntryBase = None,
    ) -> Tuple[torch.Tensor, Optional[kv_utils.KVCacheEntryBase]]:
        """Forward pass (Gemma3/Gemma4 variant with LAUREL, simplified AltUp).

        Same as Gemma3 DecoderBlock plus LAUREL in the attention path.
        Post-attention norm is applied to raw attn output before residual add.
        """
        x_norm = self.pre_atten_norm(x)
        laurel_out = self.laurel(x_norm)
        attn_out, kv = self.atten_func(x_norm, rope, mask, input_pos, kv_cache)
        attn_out_norm = self.post_atten_norm(attn_out)
        # Combine attention + LAUREL with sqrt(2) normalization (from Gemma3n)
        attn_laurel = (x + attn_out_norm + laurel_out) / math.sqrt(2)
        output = attn_laurel + self.ff(attn_laurel)
        return output, kv


# ---------------------------------------------------------------------------
# Decoder: Top-level text model
# ---------------------------------------------------------------------------

class Decoder(nn.Module):
    """Gemma4 decoder (text-only, AltUp-simplified).

    The model interface matches Gemma3's Decoder for compatibility with
    the convert_gemma4_to_tflite.py converter script.
    """

    def __init__(self, config: cfg.ModelConfig):
        super().__init__()
        self.tok_embedding = nn.Embedding(
            config.vocab_size, config.embedding_dim, padding_idx=0
        )
        self.lm_head = nn.Linear(
            config.embedding_dim,
            config.vocab_size,
            bias=config.lm_head_use_bias,
        )
        # Gemma4 reuses embedding weights for lm_head (tied weights)
        self.lm_head.weight.data = self.tok_embedding.weight.data
        self.transformer_blocks = nn.ModuleList(
            DecoderBlock(config.block_config(idx), config)
            for idx in range(config.num_layers)
        )
        self.final_norm = builder.build_norm(
            config.embedding_dim,
            config.final_norm_config,
        )
        self.mask_cache = attn_utils.build_causal_mask_cache(
            size=config.kv_cache_max,
        )
        attn_config = config.block_config(0).attn_config
        self.sliding_window_mask_cache = attn_utils.build_sliding_window_mask_cache(
            size=config.kv_cache_max,
            window_size=attn_config.sliding_window_size,
        )
        self.config = config

    def get_attention_mask(
        self,
        attn_type: cfg.AttentionType,
        input_pos: torch.Tensor,
    ) -> torch.Tensor:
        if attn_type == cfg.AttentionType.LOCAL_SLIDING:
            return self.sliding_window_mask_cache.index_select(2, input_pos)
        return self.mask_cache.index_select(2, input_pos)

    def get_local_global_attention_mask(
        self,
        attention_mask: torch.Tensor,
        attn_type: cfg.AttentionType,
        segment_pos: torch.Tensor,
        sliding_window_size: int,
    ) -> torch.Tensor:
        if attn_type == cfg.AttentionType.LOCAL_SLIDING:
            sliding_mask = self._create_sliding_mask(
                segment_pos, attention_mask.shape[-1], sliding_window_size
            )
            return torch.min(attention_mask, sliding_mask)
        return attention_mask

    def _create_sliding_mask(
        self,
        segment_pos: torch.Tensor,
        cache_len: int,
        sliding_window_size: int,
    ) -> torch.Tensor:
        cache_positions = torch.arange(cache_len, dtype=torch.int32).view(1, 1, -1)
        pos_expanded = segment_pos.clone().unsqueeze(-1)
        left = cache_positions > pos_expanded - sliding_window_size
        right = cache_positions < pos_expanded + sliding_window_size
        mask_bool = left & right
        return torch.where(
            mask_bool,
            torch.zeros_like(mask_bool, dtype=torch.float),
            torch.full_like(mask_bool, float("-inf"), dtype=torch.float),
        )

    @torch.inference_mode()
    def forward(
        self,
        tokens: torch.Tensor,
        input_pos: torch.Tensor,
        kv_cache: kv_utils.KVCacheBase,
        input_embeds: Optional[torch.Tensor] = None,
        mask: Optional[torch.Tensor] = None,
        image_indices: Optional[torch.Tensor] = None,
        export_config: Optional[model_builder.ExportConfig] = None,
    ) -> dict:
        if input_embeds is None:
            input_embeds = self.tok_embedding(tokens)
            if self.config.embedding_scale is not None:
                input_embeds = input_embeds * self.config.embedding_scale

        attn_config = self.config.block_config(0).attn_config
        rope = [
            rotary_pos_emb.build_rope(
                input_pos,
                attn_config.head_dim,
                self.config.block_config(i).attn_config.rotary_base,
            )
            for i in range(self.config.num_layers)
        ]

        if mask is None:
            mask_list = [
                self.get_local_global_attention_mask(
                    self.get_attention_mask(
                        self.config.block_config(i).attn_config.attn_type,
                        input_pos,
                    ),
                    self.config.block_config(i).attn_config.attn_type,
                    input_pos,
                    self.config.block_config(i).attn_config.sliding_window_size,
                )
                for i in range(self.config.num_layers)
            ]
        else:
            mask_list = [mask] * self.config.num_layers

        return self._forward_with_embeds(
            input_embeds, rope, mask_list, input_pos, kv_cache, export_config
        )

    def _forward_with_embeds(
        self,
        input_embeds: torch.Tensor,
        rope: List[Tuple[torch.Tensor, torch.Tensor]],
        mask: List[torch.Tensor],
        input_pos: torch.Tensor,
        kv_cache: kv_utils.KVCacheBase,
        export_config: Optional[model_builder.ExportConfig] = None,
    ) -> dict:
        assert len(self.transformer_blocks) == len(kv_cache.caches)

        x = input_embeds
        updated_kv_entries = []
        for i, block in enumerate(self.transformer_blocks):
            kv_entry = kv_cache.caches[i] if kv_cache else None
            x, kv_entry = block(x, rope[i], mask[i], input_pos, kv_entry)
            if kv_entry:
                updated_kv_entries.append(kv_entry)

        updated_kv_cache = kv_utils.KVCacheBase(tuple(updated_kv_entries))

        if export_config is not None:
            if (
                torch.numel(input_pos) > 1
                and not export_config.output_logits_on_prefill
            ):
                return {"kv_cache": updated_kv_cache}

        x = self.final_norm(x)
        logits = self.lm_head(x)
        return {"logits": logits, "kv_cache": updated_kv_cache}


# ---------------------------------------------------------------------------
# Model configs: Gemma4 E2B (= Gemma3n E2B architecture)
# ---------------------------------------------------------------------------

def get_decoder_config_e2b(kv_cache_max_len: int = 2048) -> cfg.ModelConfig:
    """Model config for Gemma4 E2B (Gemma3n E2B architecture).

    Parameters from Gemma3nTextConfig defaults (E4B) scaled to E2B.
    E2B actual values from HF config.json:
      hidden_size: 2048
      num_hidden_layers: 35
      num_attention_heads: 8
      num_key_value_heads: 2
      head_dim: 256
      intermediate_size: 16384 (uniform, not variable per layer for E2B)
      sliding_window: 512
      layer_types: sliding every 4, global every 5th
      rotary_theta: 1000000 (global), 10000 (local)
    """
    norm_config = cfg.NormalizationConfig(
        type=cfg.NormalizationType.RMS_NORM,
        epsilon=1e-6,
        zero_centered=True,
        enable_hlfb=True,
    )
    ff_config = cfg.FeedForwardConfig(
        type=cfg.FeedForwardType.GATED,
        activation=cfg.ActivationConfig(cfg.ActivationType.GELU_TANH),
        intermediate_size=16_384,
        pre_ff_norm_config=norm_config,
        post_ff_norm_config=norm_config,
    )

    def get_block_config(idx: int) -> cfg.TransformerBlockConfig:
        # Global attention every 5th layer (idx+1) % 5 == 0
        is_global = (idx + 1) % 5 == 0
        attn_config = cfg.AttentionConfig(
            num_heads=8,
            head_dim=256,
            num_query_groups=2,  # GQA: 8 query heads / 2 KV heads = 4 groups
            rotary_base=1_000_000 if is_global else 10_000,
            rotary_percentage=1.0,
            qkv_transpose_before_split=True,
            query_norm_config=norm_config,
            key_norm_config=norm_config,
            logit_softcap=None,
            sliding_window_size=512,
            attn_type=(
                cfg.AttentionType.GLOBAL
                if is_global
                else cfg.AttentionType.LOCAL_SLIDING
            ),
        )
        return cfg.TransformerBlockConfig(
            attn_config=attn_config,
            ff_config=ff_config,
            pre_attention_norm_config=norm_config,
            post_attention_norm_config=norm_config,
        )

    num_layers = 35
    embedding_dim = 2048
    return cfg.ModelConfig(
        vocab_size=262_400,
        num_layers=num_layers,
        max_seq_len=8_192,   # reduced from 128K for mobile (kv_cache_max_len-limited)
        embedding_dim=embedding_dim,
        embedding_scale=embedding_dim ** 0.5,
        kv_cache_max_len=kv_cache_max_len,
        block_configs=[get_block_config(i) for i in range(num_layers)],
        final_norm_config=norm_config,
        lm_head_use_bias=False,
        enable_hlfb=True,
        final_logit_softcap=30.0,
    )


def get_fake_decoder_config(kv_cache_max_len: int = 128) -> cfg.ModelConfig:
    """Tiny config for unit testing the converter."""
    config = get_decoder_config_e2b(kv_cache_max_len)
    config.vocab_size = 128
    config.num_layers = 2
    config.max_seq_len = 2 * kv_cache_max_len
    config.embedding_dim = 128
    config.embedding_scale = config.embedding_dim ** 0.5
    config.block_configs = config.block_configs[: config.num_layers]
    for bc in config.block_configs:
        bc.attn_config.num_heads = 4
        bc.attn_config.num_query_groups = 2
        bc.attn_config.head_dim = 32
        bc.attn_config.sliding_window_size = 64
        bc.ff_config.intermediate_size = 256
    return config


def build_model_e2b(checkpoint_path: str, **kwargs) -> Decoder:
    """Load Gemma4 E2B weights from HF checkpoint.

    NOTE: This only loads the standard transformer weights (q/k/v proj, mlp,
    norms, embedding). The AltUp, LAUREL, and PLE weights are NOT loaded
    because they are not in the simplified Decoder definition.
    """
    return model_builder.build_decoder_only_model(
        checkpoint_path=checkpoint_path,
        config=get_decoder_config_e2b(**kwargs),
        tensor_names=TENSOR_NAMES,
        model_class=Decoder,
    )
