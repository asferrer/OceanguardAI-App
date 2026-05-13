"""Experiments config: 9 grid + dataset paths + class mapping.

Port directo de RT-DETRv2/experiments/config.py adaptado a Gemma 4 LoRA.
"""
from pathlib import Path

# === Paths Synology ===
SYNO_ROOT = Path(r"C:\Users\aleja\SynologyDrive\Datasets\Deteccion_Residuos")
REAL_MANIFEST = SYNO_ROOT / "real_annotations_cleansea_ocean-garbage_neural-ocean_combined.json"

# REAL image roots (probar en este orden; primer match gana)
REAL_IMAGE_ROOTS = [
    SYNO_ROOT / "CleanSea/CocoFormatDataset/train_coco/JPEGImages",
    SYNO_ROOT / "CleanSea/JPEGImages",
    SYNO_ROOT / "Ocean_garbage/train",
    SYNO_ROOT / "Ocean_garbage/valid",
    SYNO_ROOT / "Ocean_garbage/test",
    SYNO_ROOT / "Neural_Ocean/train",
    SYNO_ROOT / "Neural_Ocean/valid",
    SYNO_ROOT / "Neural_Ocean/test",
]

# SYNTH DenSea
SYNTH_MANIFEST = SYNO_ROOT / "dataset_sintetico_densea/synthetic_dataset.json"
SYNTH_IMAGES_ROOT = SYNO_ROOT / "dataset_sintetico_densea/images"

# === Class mapping (Gemma 4 label space) ===
# COCO category_id -> (label, material) usados en JSONL assistant content
CATEGORY_MAP = {
    0: ("plastic_bottle",  "Plastic"),
    1: ("metal_can",       "Metal"),
    2: ("fishing_net",     "Plastic"),
    3: ("glove",           "Latex"),
    4: ("face_mask",       "Fabric"),
    5: ("metal_debris",    "Metal"),
    6: ("plastic_debris",  "Plastic"),
    7: ("tire",            "Rubber"),
}

# Label inverso para eval (label string → category_id)
LABEL_TO_CAT = {v[0]: k for k, v in CATEGORY_MAP.items()}

# Class names para tablas
CLASS_NAMES = [
    "Bottle", "Can", "Fishing_Net", "Glove",
    "Mask", "Metal_Debris", "Plastic_Debris", "Tire",
]

# Minoritarias para exp08/09
MINORITY_CAT_IDS = [1, 5]  # Can, Metal_Debris

# === Grid de 9 experimentos ===
# Cada exp define:
#   synth_n: cuántas synth samples (0 a 3000)
#   real_n:  cuántas real samples (0 a 3000)
#   real_strategy: "stratified" | "minority_only" | "inverse_proportional" | None
#
# Cap total train por exp: 3000 imgs (1 epoch = 94 steps a bs=32 effective)
TRAIN_CAP = 3000
TEST_HOLDOUT_SIZE = 1000

EXPERIMENTS = [
    {
        "name": "exp01_synth100",
        "synth_n": 3000, "real_n": 0,
        "synth_strategy": "random", "real_strategy": None,
    },
    {
        "name": "exp02_synth100_real10",
        "synth_n": 2700, "real_n": 300,
        "synth_strategy": "random", "real_strategy": "stratified",
    },
    {
        "name": "exp03_synth100_real25",
        "synth_n": 2250, "real_n": 750,
        "synth_strategy": "random", "real_strategy": "stratified",
    },
    {
        "name": "exp04_synth100_real50",
        "synth_n": 1500, "real_n": 1500,
        "synth_strategy": "random", "real_strategy": "stratified",
    },
    {
        "name": "exp05_synth100_real75",
        "synth_n": 750, "real_n": 2250,
        "synth_strategy": "random", "real_strategy": "stratified",
    },
    {
        "name": "exp06_synth100_real100",
        "synth_n": 1500, "real_n": 1500,
        "synth_strategy": "random", "real_strategy": "stratified",
    },
    {
        "name": "exp07_real100",
        "synth_n": 0, "real_n": 3000,
        "synth_strategy": None, "real_strategy": "stratified",
    },
    {
        "name": "exp08_real_synth_minor",
        "synth_n": 1000, "real_n": 2000,
        "synth_strategy": "minority_only", "real_strategy": "stratified",
    },
    {
        "name": "exp09_real_synth_prop",
        "synth_n": 1500, "real_n": 1500,
        "synth_strategy": "inverse_proportional", "real_strategy": "stratified",
    },
]

# === Detection prompt (idéntico al de production app, alineado con etapa1_v2) ===
SYSTEM_PROMPT = (
    "You are a marine debris detection system. Output ONLY valid JSON arrays. "
    "Never include explanations, markdown, or text outside the JSON."
)

DETECTION_PROMPT = (
    "Detect ALL marine debris and litter in this image. Output ONLY a JSON array.\n\n"
    "For each object, provide:\n"
    "- \"box_2d\": bounding box as [y_min, x_min, y_max, x_max], integers 0-1000\n"
    "- \"label\": ONE of these 8 labels (snake_case, lowercase): "
    "plastic_bottle, metal_can, fishing_net, glove, face_mask, metal_debris, "
    "plastic_debris, tire\n"
    "- \"material\": Plastic, Metal, Glass, Rubber, Fabric, Latex\n\n"
    "Output format:\n"
    "[{\"box_2d\": [y_min, x_min, y_max, x_max], \"label\": \"object_type\", "
    "\"material\": \"material_type\"}]\n\n"
    "If no debris is found, output: []"
)

# === Paths repo ===
REPO_ROOT = Path(__file__).resolve().parent.parent  # finetune/
EXP_ROOT = REPO_ROOT / "experiments"
SPLITS_DIR = EXP_ROOT / "splits"
CONFIGS_DIR = EXP_ROOT / "configs"
RESULTS_DIR = EXP_ROOT / "results"
LOGS_DIR = REPO_ROOT / "outputs/logs"
EXP_OUTPUTS_BASE = REPO_ROOT / "outputs"

# Template de YAML para entrenar (referencia, copy + override)
ETAPA1_TEMPLATE = REPO_ROOT / "configs/etapa1.yaml"

# Eval test hold-out (compartido entre todos los experimentos)
TEST_HOLDOUT_JSONL = SPLITS_DIR / "real_test_holdout.jsonl"

# Train cap por experimento (override del template)
TRAIN_EPOCHS = 1
SAVE_STEPS = 50
SAVE_TOTAL_LIMIT = 1
