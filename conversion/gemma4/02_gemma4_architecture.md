# Gemma4 Architecture Analysis

## Source

Transformers 4.57.3 does NOT contain `gemma4`. The closest public model is `gemma3n` (Gemma 3 Nano), which shares the same novel architectural features described in Google's Gemma 4 announcements. Based on the litert-community model card for `gemma-4-E2B-it-litert-lm` and the transformers `gemma3n` implementation, Gemma 4 E2B is architecturally equivalent to Gemma 3n E2B with a different training recipe.

**This document uses Gemma3n as the proxy for Gemma4**, as they share identical code paths in transformers.

---

## Gemma3n E2B Text Config defaults (= Gemma4 E2B proxy)

```
vocab_size:                 262,400
vocab_size_per_layer_input: 262,144
hidden_size:                2,048     # embedding dim
hidden_size_per_layer_input: 256      # PLE embedding dim
intermediate_size:          16,384    # MLP intermediate (can vary per layer)
num_hidden_layers:          35
num_attention_heads:        8
num_key_value_heads:        2         # GQA (4 queries per KV head)
head_dim:                   256
rope_theta:                 1,000,000  # global RoPE base
rope_local_base_freq:       10,000    # local sliding window RoPE base
sliding_window:             512
final_logit_softcapping:    30.0
layer_types:                sliding_attention every 4, full_attention every 5th
altup_num_inputs:           4         # AltUp parallel streams
altup_active_idx:           0         # which stream gets the actual computation
altup_coef_clip:            120.0
altup_correct_scale:        True
num_kv_shared_layers:       15        # last 15 layers share KV cache
laurel_rank:                64        # LAUREL low-rank dimension
```

---

## Architecture Component Analysis

### 1. Standard Components (exportable, already in ai_edge_torch)

| Component | Status | Notes |
|-----------|--------|-------|
| RMSNorm (manual) | Exportable | Must use manual implementation, not `torch.nn.RMSNorm` (vhlo.rsqrt_v2 incompatibility) |
| GQA attention | Exportable | Standard q/k/v projections, RoPE |
| SwiGLU/GeGLU MLP | Exportable | gate_proj + up_proj + down_proj |
| RoPE (global) | Exportable | standard rotary embeddings |
| Local sliding window | Exportable | Already in gemma3 template |
| Token embedding | Exportable | standard nn.Embedding |
| SigLIP2 vision encoder | Exportable | Identical to gemma3, reuse 1-to-1 |

### 2. Novel Components (Gemma3n / Gemma4 specific)

#### 2a. AltUp (Alternating Updates)

**What it does**: Maintains 4 parallel hidden state streams (`altup_num_inputs=4`). At each layer:
- `predict`: Uses a trainable linear map to predict all 4 stream updates from the active stream
- Active stream (idx=0) goes through the actual attention + MLP
- `correct`: Propagates the innovation (actual output - predicted output) back to all streams

**Key tensors**: 4D `(altup_num_inputs, batch, seq_len, hidden)` stacked tensor.

**Export feasibility**: TESTED — `torch.export.export` succeeds on AltUp-like operations including:
- 4D tensor stacking and indexing
- Dynamic `torch.matmul` with per-sample coefficient matrices
- `torch.tanh` router + coefficient computation

**Blocker**: The stacked 4D tensor `(4, B, T, D)` has **batch dimension 0** (altup_num_inputs), which is a fixed static constant (4). This is exportable. However, the `ai_edge_torch.convert()` API expects a fixed-shape model — the 4D hidden state is an intermediate, not an input, so it stays static.

#### 2b. LAUREL (Learned Augmented Residual Layer)

**What it does**: A low-rank linear bottleneck applied in parallel to attention:
```
laurel_out = post_laurel_norm(linear_right(linear_left(normed_x)))
attn_with_laurel = (attn_gated + laurel_out) / sqrt(2)
```

**Export feasibility**: TRIVIALLY EXPORTABLE — two Linear layers + RMSNorm + addition.

#### 2c. PLE (Per-Layer Embeddings / `per_layer_input`)

**What it does**: Each layer receives a per-layer input embedding from a separate vocabulary embedding table:
- Separate vocab: `embed_tokens_per_layer` with vocab `262,144` × `(num_layers × hidden_per_layer=256)`  
- Projected to `(num_layers × 256)` then sliced per layer
- Applied inside each DecoderLayer via a gate + projection

**Export feasibility**: The per-layer input is PRECOMPUTED before the main decode loop (in `Gemma3nTextModel.get_per_layer_inputs()`). This means the conversion must handle it as a second model input. It is a standard Linear layer operation — exportable.

#### 2d. Shared KV Cache (`num_kv_shared_layers=15`)

**What it does**: Last 15 layers reuse the KV cache from their matching non-shared layer (same attention type — sliding or global). The attention projection is still computed, only K and V lookups are shared.

**Export feasibility**: Problematic. The `ai_edge_torch` KV cache model uses pre-allocated `KVCache` buffers indexed by layer. Shared layers would reference the same buffer, which conflicts with the static allocation model. This requires either:
1. Duplicating the KV cache entry (wastes memory but works)
2. Implementing a custom KV lookup that references another layer's cache slot

**Current ai_edge_torch API**: `ModelConfig` has no concept of KV sharing. The `TransformerBlockConfig.kv_cache_max_len` field only controls per-block cache size.

#### 2e. Activation Sparsity (`gaussian_topk`)

**What it does**: In some MLP layers, applies Gaussian TopK gating before the activation function (sparsifies the gate projection).

**Export feasibility**: Uses `torch.distributions.normal.Normal(0,1).icdf()` which IS `torch.export`-compatible. Tested — SUCCESS.

---

## Diff Table: Gemma3 (1B) vs Gemma3n/4 (E2B)

| Feature | Gemma3 1B | Gemma3n/4 E2B | Exportable? |
|---------|-----------|----------------|------------|
| Hidden size | 1,152 | 2,048 | N/A |
| Layers | 26 | 35 | N/A |
| Heads | 4 | 8 | N/A |
| KV heads | 1 | 2 | N/A |
| Head dim | 256 | 256 | N/A |
| Vocab size | 262,144 | 262,400 | N/A |
| MLP | Standard GATED | GATED + activation sparsity | Yes (sparse is optional) |
| Attention | Local/Global interleaved (1-in-6) | Local/Global interleaved (1-in-5) | Yes |
| KV cache | Standard (1 per layer) | Shared KV last 15 layers | Partial |
| RoPE | Dual base (local 10k, global 1M) | Dual base (local 10k, global 1M) | Yes |
| Hidden state | Single stream | AltUp 4× streams | Yes |
| Per-layer emb | None | PLE (separate vocab + projection) | Yes (as extra input) |
| LAUREL | None | Low-rank bottleneck in attention | Yes |
| Post-attn norm | Yes | Yes | Yes |
| Vision encoder | SigLIP2 (896×896, 256 tokens) | SigLIP2 (same) | Yes |
| Logit softcap | None | 30.0 | Yes |

---

## Critical Blockers for `.litertlm` Production

### Blocker 1: `.litertlm` format is proprietary

The public `convert_to_tflite.py` pipeline produces `.tflite`. The `.litertlm` format consumed by MediaPipe `LlmInference` and `LlmInferenceSession` is produced by a Google-internal `model_packager` binary that bundles:
- The decoder `.tflite`  
- The image encoder `.tflite`  
- A `model_metadata.pb` with tokenizer config, system prompt, and quantization metadata

This packager is NOT publicly available. The `litert-community/gemma-4-E2B-it-litert-lm` model was produced with Google's internal pipeline.

**There is no workaround for this blocker in the public toolchain.**

### Blocker 2: Shared KV Cache

`ai_edge_torch 0.4.0` ModelConfig has no field for KV sharing between layers. The 15 shared layers in Gemma3n/4 would need either:
- Duplicating KV buffers (wastes 15× KV cache slots of memory — ~350MB at 2048 ctx)
- Patching `ai_edge_torch` KV cache internals

A workable approximation: ignore sharing and allocate separate KV caches per layer (correctness impact: the 15 shared layers will compute new KV — semantically different from the trained model).

### Blocker 3: 4D AltUp hidden state changes model interface

The `ai_edge_torch` generative API expects:
```python
model.forward(tokens, input_pos, kv_cache) -> {logits, kv_cache}
```

With AltUp, the hidden state is 4D `(4, B, T, D)` internally. This does not change the external interface (tokens in → logits out), but the internal KV cache must also hold 4× the hidden states per layer, which the current API doesn't support.

### Blocker 4: PLE as extra model input

The per-layer embedding (`per_layer_input`) must be provided at every decode step. This adds a second input tensor to every call (in addition to `tokens` and `input_pos`). The current `convert_to_tflite()` API in ai_edge_torch does not expose a clean way to add this — it would require extending the `ExportableModule` wrapper.

---

## What IS achievable today (text-only, no .litertlm)

A text-only Gemma3n decoder (no vision) with these approximations:
1. No KV sharing (separate cache per layer) — semantically wrong but structurally sound
2. PLE passed as a fixed/zero tensor (degrades quality, not correctness)
3. No activation sparsity (omit gaussian_topk) — minor quality degradation
4. AltUp simplified to active-stream-only (convert 4× AltUp to single-stream) — significant quality degradation
5. Produce `.tflite` not `.litertlm`

**This would produce a non-working model** (wrong outputs due to AltUp/PLE removal) but could validate the conversion pipeline end-to-end.

---

## Gemma4 vs Gemma3n: The Actual Difference

Based on the `litert-community/gemma-4-E2B-it-litert-lm` model card:
- Gemma 4 is the 4th generation; Gemma 3n is its mobile-optimized variant
- They share the same architecture (AltUp, LAUREL, PLE, shared KV)
- Gemma 4 has a larger context window (128K tokens vs 32K)
- Gemma 4 has improved SigLIP2 (higher resolution multimodal input option)
- The architecture code is identical; the model weights differ

`transformers` 4.57.3 uses `gemma3n` code for both Gemma 3n AND Gemma 4 (since they are architecturally identical).
