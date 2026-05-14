# OceanGuard AI — Fine-tune Gemma 4 E2B Vision

Pipeline en dos etapas para adaptar **Gemma 4 E2B** (vision-language) a detección de
basura marina con salida JSON de bounding boxes.

## Stack

- **Modelo**: `unsloth/gemma-4-E2B-it` (Apache 2.0)
- **Framework**: [Unsloth](https://github.com/unslothai/unsloth) (1.5x más rápido, 60% menos VRAM vs FA2)
- **Hardware**: RTX 5090 32GB (Blackwell sm_120, torch 2.7.0+cu128)

## Estructura

```
finetune/
├── configs/
│   ├── etapa1.yaml          # Hiperparámetros etapa 1 (warmup geometría)
│   └── etapa2.yaml          # Hiperparámetros etapa 2 (refinamiento)
├── data/
│   ├── etapa1_train.jsonl   # ~10k samples COCO 8-class (generado por FT1)
│   ├── etapa1_val.jsonl
│   ├── etapa2_train.jsonl   # ~449 samples curated (generado por FT1)
│   └── etapa2_val.jsonl
├── outputs/
│   ├── lora_etapa1/         # checkpoints + adapter etapa 1
│   └── lora_etapa2/         # checkpoints + adapter final
├── train_etapa1.py          # Etapa 1: language-only LoRA, JSON warmup
├── train_etapa2.py          # Etapa 2: + vision LoRA, VQA mix anti-forgetting
├── eval_model.py            # Inference batch sobre val set -> predictions.jsonl
├── infer_single.py          # Smoke test 1 imagen
└── requirements.txt
```

## Instalación

```bash
# 1) Setup CUDA + PyTorch para Blackwell
pip install torch==2.7.0 --index-url https://download.pytorch.org/whl/cu128

# 2) Unsloth + deps
pip install -r requirements.txt

# 3) Validar (sin GPU)
python -c "from unsloth import FastVisionModel; print('ok')"
```

## Uso

### Etapa 1 (warmup geometría + JSON)

```bash
python train_etapa1.py --config configs/etapa1.yaml
```

Tiempo estimado: 3-5h con 10k samples en RTX 5090 (bs=1, grad_accum=8, 1 epoch).

### Etapa 2 (refinamiento + VQA mix)

```bash
python train_etapa2.py --config configs/etapa2.yaml
```

Requiere que `outputs/lora_etapa1/adapter/` exista. Tiempo estimado: 1-2h con 449 samples (3 epochs).

### Eval offline

```bash
python eval_model.py \
    --model unsloth/gemma-4-E2B-it \
    --adapter outputs/lora_etapa2/adapter \
    --dataset data/etapa2_val.jsonl \
    --output outputs/lora_etapa2/predictions.jsonl

# Después: mAP con script existente
python data/eval_map.py outputs/lora_etapa2/predictions.jsonl
```

### Smoke test 1 imagen

```bash
python infer_single.py \
    --model unsloth/gemma-4-E2B-it \
    --adapter outputs/lora_etapa1/adapter \
    --image data/samples/test.jpg
```

## Decisiones de diseño documentadas

### API Unsloth (validada contra notebook oficial mayo 2026)

- `FastVisionModel.from_pretrained()` devuelve `(model, processor)` (no tokenizer).
- `get_chat_template(processor, "gemma-4")` aplica el template correcto.
- `UnslothVisionDataCollator(model, processor, train_on_responses_only=True)` calcula loss solo sobre tokens del assistant — mejor para JSON output.
- Args **obligatorios** en `SFTConfig` para vision:
  - `remove_unused_columns=False`
  - `dataset_text_field=""`
  - `dataset_kwargs={"skip_prepare_dataset": True}`

### Etapa 1: language-only LoRA

- `finetune_vision_layers=False` para no degradar el encoder visual con un dataset pequeño y posiblemente ruidoso al inicio.
- `r=16, alpha=16, target_modules="all-linear"`.
- Loss solo sobre assistant tokens (`train_on_responses_only=True`).

### Etapa 2: abre vision encoder + VQA mix

- `finetune_vision_layers=True, r=8` (rank más pequeño para no sobreajustar).
- `merge_etapa1_first=True`: hace `merge_and_unload()` del LoRA de etapa 1 dentro del modelo base antes de aplicar el nuevo LoRA — evita layered-LoRA quirks.
- `vqa_mix.enabled=true, ratio=0.10`: mezcla 10% LLaVA-Instruct-150K subset via `interleave_datasets` para mitigar catastrophic forgetting (ver [emt paper](https://yx-s-z.github.io/emt/)).

### Formato bbox

- Coordenadas normalizadas `[ymin, xmin, ymax, xmax]` en rango `[0, 1000]` (consistente con la convención `box_2d` que ya usa la app).
- Loss se computa sobre el JSON string completo — modelo aprende el formato como secuencia tokenizada.

## Riesgos conocidos / mitigaciones

| Riesgo | Mitigación |
|--------|-----------|
| CUBLAS_STATUS_EXECUTION_FAILED en Blackwell | Limpiar `/tmp/unsloth_compiled_cache`; si persiste, setear `TORCHDYNAMO_DISABLE=1` + `UNSLOTH_COMPILE_DISABLE=1`. Issue [#5154](https://github.com/unslothai/unsloth/issues/5154). |
| Falsos OOM con 25+ GB libres | Mismas env vars que arriba. |
| GGUF export pierde capability vision | Issue [#2290](https://github.com/unslothai/unsloth/issues/2290) (Gemma 3, posiblemente arrastrado a Gemma 4). Validar con `llama-mtmd-cli` antes de empaquetar para Android. |
| JSON malformado en eval | `temperature=0.1` + prompt explícito "Return ONLY the JSON array, no extra text". Considerar guided decoding con Outlines en producción. |
| Catastrophic forgetting VQA general | Etapa 2 mezcla 10% LLaVA-Instruct-150K (configurable). |

## Validación de imports (sin GPU)

```bash
python -c "from unsloth import FastVisionModel; print('ok')"
python -c "from unsloth.trainer import UnslothVisionDataCollator; print('ok')"
python -c "from trl import SFTConfig, SFTTrainer; print('ok')"
```

## Referencias

- Notebook oficial: https://github.com/unslothai/notebooks/blob/main/nb/Gemma4_(E2B)-Vision.ipynb
- Docs Gemma 4: https://unsloth.ai/docs/models/gemma-4/train
- Vision fine-tuning: https://unsloth.ai/docs/basics/vision-fine-tuning
- Blackwell setup: https://unsloth.ai/docs/blog/fine-tuning-llms-with-blackwell-rtx-50-series-and-unsloth.md
- HF cookbook (VLM grounding): https://huggingface.co/learn/cookbook/en/fine_tuning_vlm_object_detection_grounding
- Catastrophic forgetting (Zhai 2024): https://proceedings.mlr.press/v234/zhai24a/zhai24a.pdf
