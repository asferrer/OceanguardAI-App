# Research: Gemma3 Template Analysis for Gemma4 Converter

## Toolchain in Docker image `mobile-model-conversion:latest`

- `ai-edge-torch` 0.4.0 (also `ai_edge_torch` in-place, renamed to `litert-torch` upstream)
- `ai-edge-litert` 1.2.0
- `ai-edge-quantizer` 0.1.x
- `jaxlib` 0.7.1 (installed), but must be **downgraded to 0.4.36** to match the StableHLO version
- `transformers` 4.57.3 (has `gemma3` and `gemma3n` but NOT `gemma4`)
- `torch` 2.6.0+cu124

### Critical environment blocker (RESOLVED)

The `.so` `/usr/local/lib/python3.11/site-packages/ai_edge_litert/_pywrap_tensorflow_interpreter_wrapper.so` has GNU_STACK flag set to `RWE` (Read-Write-Execute). Docker Desktop on Windows does not allow marking stack as executable. Fix with patchelf at container start:

```bash
apt-get install -y patchelf
patchelf --clear-execstack /usr/local/lib/python3.11/site-packages/ai_edge_litert/_pywrap_tensorflow_interpreter_wrapper.so
```

### StableHLO version mismatch (RESOLVED)

With `jaxlib 0.7.1`, JAX emits `StableHLO_v1.10.8` which `ai_edge_litert 1.2.0` cannot parse (only handles up to `StableHLO_v1.9.1`). Fix by downgrading jax/jaxlib:

```bash
pip install 'jaxlib==0.4.36' 'jax==0.4.36'
```

After both fixes, `ai_edge_torch.convert(model, sample_inputs)` works.

---

## Gemma3 Template File Map

| File | Role |
|------|------|
| `gemma3/gemma3.py` | `Gemma3MM` multimodal wrapper + `Gemma3MMConfig` |
| `gemma3/decoder.py` | `Decoder` class + `get_decoder_config_1b()` + `build_model_1b()` |
| `gemma3/image_encoder.py` | `SiglipVisionEncoderWithExit` + `SiglipExit` pooling |
| `gemma3/convert_gemma3_to_tflite.py` | CLI converter, calls `converter.convert_to_tflite()` |

---

## How HF model maps to the local PyTorch definition

### Tensor name mapping (decoder)

The loader uses `loading_utils.ModelLoader.TensorNames` to map HF safetensors keys to module attributes. Two formats are supported (safetensors vs kaggle):

```python
# HF safetensors format
ff_up_proj   = "model.layers.{}.mlp.up_proj"
ff_down_proj = "model.layers.{}.mlp.down_proj"
ff_gate_proj = "model.layers.{}.mlp.gate_proj"
attn_query_proj = "model.layers.{}.self_attn.q_proj"
attn_key_proj   = "model.layers.{}.self_attn.k_proj"
attn_value_proj = "model.layers.{}.self_attn.v_proj"
attn_output_proj = "model.layers.{}.self_attn.o_proj"
attn_query_norm  = "model.layers.{}.self_attn.q_norm"
attn_key_norm    = "model.layers.{}.self_attn.k_norm"
pre_attn_norm   = "model.layers.{}.input_layernorm"
post_attn_norm  = "model.layers.{}.post_attention_layernorm"
pre_ff_norm     = "model.layers.{}.pre_feedforward_layernorm"
post_ff_norm    = "model.layers.{}.post_feedforward_layernorm"
embedding       = "model.embed_tokens"
final_norm      = "model.norm"
lm_head         = None  # shared with embedding
```

### Image encoder tensor names (SigLIP2)

```python
ff_up_proj   = "vision_tower.vision_model.encoder.layers.{}.mlp.fc1"
ff_down_proj = "vision_tower.vision_model.encoder.layers.{}.mlp.fc2"
attn_query_proj = "vision_tower.vision_model.encoder.layers.{}.self_attn.q_proj"
...
embedding    = "vision_tower.vision_model.embeddings.patch_embedding"
embedding_position = "vision_tower.vision_model.embeddings.position_embedding.weight"
final_norm   = "vision_tower.vision_model.post_layernorm"
```

---

## Quantization

`converter.convert_to_tflite()` accepts `quantize: bool`. When True, uses:

```python
quant_recipes.full_int8_dynamic_recipe()  # INT8 dynamic quantization
```

Available recipes:
- `full_int8_dynamic_recipe()` — INT8 activations + INT8 weights, computed dynamically
- `full_int8_weight_only_recipe()` — INT8 weights only, FP32 activations  
- `full_fp16_recipe()` — FP16 weights

The `litert-community/gemma-4-E2B-it-litert-lm` model card does not specify the exact recipe; likely INT4 weight-only (MediaPipe tasks-genai uses 4-bit by default for LiteRT-LM).

---

## Export Config (ExportConfig)

```python
@dataclass
class ExportConfig:
    output_logits_on_prefill: bool = False
    prefill_mask: Optional[torch.Tensor | List[torch.Tensor]] = None
    decode_mask: Optional[torch.Tensor | List[torch.Tensor]] = None
    kvcache_cls: type = kv_utils.KVCache
    decode_batch_size: int = 1
```

For Gemma3 (with sliding window attention):
- `kvcache_cls = kv_cache.KVCacheTransposed` (from `experimental`)
- Prefill masks are pre-computed causal masks per seq-length variant
- Multiple `prefill_seq_lens` produce multiple tflite signatures

---

## ModelConfig supported fields (ai_edge_torch 0.4.0)

```python
cfg.ModelConfig(
    vocab_size,
    num_layers,
    max_seq_len,
    embedding_dim,
    block_configs,           # list of TransformerBlockConfig per layer
    final_norm_config,
    embedding_scale=None,
    embedding_use_bias=False,
    image_embedding=None,    # ImageEmbeddingConfig for VL models
    num_mm_tokens_per_image=None,
    lm_head_use_bias=False,
    lm_head_share_weight_with_embedding=True,
    enable_hlfb=False,
    kv_cache_max_len=0,
    final_logit_softcap=None,
)
```

### AttentionConfig

```python
cfg.AttentionConfig(
    num_heads, head_dim, num_query_groups,
    rotary_base=10000,
    rotary_percentage=None,
    qkv_transpose_before_split=False,
    query_norm_config=None,   # per-head QK norm
    key_norm_config=None,
    attn_type=None,           # GLOBAL or LOCAL_SLIDING
    sliding_window_size=None,
    logit_softcap=None,
)
```

### AttentionType enum

- `cfg.AttentionType.GLOBAL` — full causal attention
- `cfg.AttentionType.LOCAL_SLIDING` — sliding window attention

### FeedForwardType enum

- `cfg.FeedForwardType.GATED` — gate + up + down (SwiGLU / GeGLU style)
- `cfg.FeedForwardType.SEQUENTIAL` — sequential (no gate, used in SigLIP2)

---

## Decoder block structure (Gemma3)

`DecoderBlock` inherits from `attention.TransformerBlock` with a custom forward:

```
x_norm  = pre_attn_norm(x)
attn, kv = attention(x_norm, rope, mask, input_pos, kv_cache)
attn_norm = post_attn_norm(attn)     # norm BEFORE residual add
x = x + attn_norm
output = x + ff(x)
```

Key difference from standard transformer: post_attn_norm is applied to the raw attention output, NOT after the residual addition.

---

## SigLIP2 image encoder (Gemma3)

- 27-layer ViT with standard sequential MLP (fc1 → GELU_TANH → fc2)
- Image size: 896x896, patch size: 14 → 4096 patches per image
- `SiglipExit` module: avg-pools patches to `num_mm_tokens_per_image=256`
- Multimodal norm (`mm_norm`): LayerNorm applied after exit
- Projection: Linear(1152 → decoder_embedding_dim)

The SigLIP2 encoder is **identical** between Gemma3 1B/4B and Gemma3n/Gemma4 in structure. The tensor names are the same. This encoder can be reused 1-to-1 for Gemma4.

---

## Output format: .tflite (not .litertlm)

The `convert_gemma3_to_tflite.py` produces a `.tflite` file. The `.litertlm` format used by MediaPipe tasks-genai is a different format produced by Google's internal `model_packager` tool (not publicly available). The public toolchain produces `.tflite` which can be loaded via `ai_edge_litert.interpreter.Interpreter` but NOT by MediaPipe's `LlmInference` API (which requires `.litertlm`).

This is a fundamental gap: the public toolchain cannot produce `.litertlm` without the Google-internal packager.
