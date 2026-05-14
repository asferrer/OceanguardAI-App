# Uploading the OceanGuard LoRA Adapter to HuggingFace

This is the step-by-step cheatsheet for publishing the trained Stage-2 LoRA adapter as
`asferrer/gemma-4-E2B-it-oceanguard-marine-debris` on the HuggingFace Model Hub. Run these
commands **from the workstation that holds the trained adapter** (the RTX 5090 training host).
Each step is idempotent except where noted.

## 0. Prerequisites

```bash
pip install -U huggingface_hub
git --version          # 2.30+ recommended
git lfs --version      # required for safetensors > 10 MiB
```

You will need a HuggingFace access token with **write** scope:
<https://huggingface.co/settings/tokens>. The username is `asferrer`.

## 1. Authenticate

```bash
huggingface-cli login
# Paste the WRITE-scope token when prompted.
# Optional: also `git config --global credential.helper store` so git pushes don't re-prompt.
```

Verify:

```bash
huggingface-cli whoami
# expected: asferrer
```

## 2. Create the model repo (one-time)

```bash
huggingface-cli repo create gemma-4-E2B-it-oceanguard-marine-debris \
    --type model \
    --organization asferrer
```

This creates an **empty** model repo at
`https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris`. If the repo already
exists the command no-ops with a warning.

## 3. Stage the adapter files

The Stage-2 training run dumps the adapter at
`finetune/outputs/lora_etapa2/adapter/` in the training repo. That directory contains:

```
adapter_config.json
adapter_model.safetensors
README.md            <-- we will overwrite this with the model card
tokenizer.json       (optional — only if tokenizer was modified)
tokenizer_config.json (optional)
special_tokens_map.json (optional)
```

Copy the prepared model card in:

```bash
cd finetune/outputs/lora_etapa2/adapter
cp ../../../docs/submission/hf_model_card.md ./README.md
```

Sanity-check what we are about to upload:

```bash
ls -lh
# adapter_config.json should be small (< 5 KiB)
# adapter_model.safetensors should be ~50 MB for r=16+r=8
# README.md should be the model card
```

**Do not** upload optimizer states (`optimizer.pt`), gradient scaler files, or the merged
base-model weights. The repo is for the LoRA adapter only.

## 4. Upload

### Option A - `huggingface-cli upload` (recommended for first push)

```bash
# From inside finetune/outputs/lora_etapa2/adapter/
huggingface-cli upload \
    asferrer/gemma-4-E2B-it-oceanguard-marine-debris \
    . \
    . \
    --repo-type=model \
    --commit-message "Initial release of OceanGuard LoRA adapter (Stage-2, r=8 vision)"
```

This streams the directory in one request, handles LFS automatically, and prints the resulting
commit URL.

### Option B - Python `huggingface_hub` (scriptable)

```python
from huggingface_hub import HfApi
api = HfApi()
api.upload_folder(
    folder_path=".",
    repo_id="asferrer/gemma-4-E2B-it-oceanguard-marine-debris",
    repo_type="model",
    commit_message="Initial release of OceanGuard LoRA adapter (Stage-2, r=8 vision)",
)
```

### Option C - manual `git clone` + `git push` (only if you want to keep a local mirror)

```bash
git lfs install
git clone https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris
cp -r finetune/outputs/lora_etapa2/adapter/* gemma-4-E2B-it-oceanguard-marine-debris/
cd gemma-4-E2B-it-oceanguard-marine-debris
cp ../docs/submission/hf_model_card.md README.md
git add .
git commit -m "Initial release of OceanGuard LoRA adapter (Stage-2, r=8 vision)"
git push
```

> Avoid `git init` inside the training output directory directly — it will conflict with the
> training repo's own `.git`. Either use Option A/B (cleanest) or clone the empty HF repo
> separately (Option C).

## 5. Verify the upload

```bash
# Adapter config sanity check
curl -fsSL \
  https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris/raw/main/adapter_config.json \
  | python -m json.tool | head -20

# Model card preview
curl -fsSL \
  https://huggingface.co/asferrer/gemma-4-E2B-it-oceanguard-marine-debris/raw/main/README.md \
  | head -40
```

End-to-end check via PEFT:

```python
from unsloth import FastVisionModel
model, tok = FastVisionModel.from_pretrained("unsloth/gemma-4-E2B-it", load_in_4bit=True)
model.load_adapter("asferrer/gemma-4-E2B-it-oceanguard-marine-debris", adapter_name="oceanguard")
model.set_adapter("oceanguard")
print("OK")
```

## 6. Optional - publish a Space demo

If you want a runnable demo Space wired to the adapter:

```bash
huggingface-cli repo create oceanguard-marine-debris-demo --type space --space_sdk gradio
```

Push a small Gradio app that wraps the inference helper from
`docs/submission/notebook_finetune.ipynb`.

## License and IP notes

- **LoRA adapter** is published under **Apache 2.0**. The adapter weights are the work of the
  OceanGuard authors; the only base-model dependency is structural (PEFT applies the adapter
  on top of Gemma 4 at load time).
- **Base model** `google/gemma-4-E2B-it` remains subject to the **Gemma Terms of Use**.
  Downstream users who download the adapter and apply it to base Gemma 4 must comply with
  those terms.
- **Training datasets** (CleanSea, Ocean_garbage, Neural_Ocean) are distributed under
  research-friendly terms (CC-BY or equivalent). The OceanGuard merge script is Apache 2.0;
  any redistribution of the merged dataset must preserve attribution to the original sources.

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `403 Forbidden` on upload | Token lacks write scope | Recreate token with **write** scope, `huggingface-cli login` again. |
| `LFS upload failed` mid-stream | Network drop on the safetensors blob | Re-run the same `huggingface-cli upload` command; LFS resumes the missing object. |
| `Repo already exists` from step 2 | Repo created in a previous attempt | Safe to ignore — proceed to step 3. |
| README renders without front-matter | YAML metadata block was reflowed | Make sure the leading `---` is on the **first line** with no blank line above. |
| `adapter_config.json` not found by PEFT | Wrong target dir uploaded | Verify the directory used in step 4 contained `adapter_config.json` at the root, not nested. |
