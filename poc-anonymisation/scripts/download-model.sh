#!/usr/bin/env bash
# Télécharge le modèle NER CamemBERT (français) au format ONNX dans models/camembert-ner.
#
# Source : https://huggingface.co/Xenova/camembert-ner (export ONNX de Jean-Baptiste/camembert-ner,
# licence MIT, entraîné sur WikiNER-fr : PER / LOC / ORG / MISC).
#
# Par défaut le modèle complet : environ 440 Mo (+ tokenizer ~2,4 Mo), ~0,8 Go de RAM à l'exécution.
# Pour la version quantifiée int8 (111 Mo, plus rapide mais qui rate des prénoms courants et des
# noms complets, voir le README) :
#   ONNX_FILE=onnx/model_quantized.onnx ./scripts/download-model.sh
# et lancer l'application avec --anonymisation.ner.model-file=onnx/model_quantized.onnx
#
# Variables : MODEL_DIR (défaut models/camembert-ner), ONNX_FILE (défaut onnx/model.onnx),
# HF_BASE_URL (miroir interne éventuel).
set -euo pipefail

cd "$(dirname "$0")/.."

MODEL_DIR="${MODEL_DIR:-models/camembert-ner}"
ONNX_FILE="${ONNX_FILE:-onnx/model.onnx}"
HF_BASE_URL="${HF_BASE_URL:-https://huggingface.co/Xenova/camembert-ner/resolve/main}"

mkdir -p "$MODEL_DIR/onnx"

fetch() {
  local remote="$1"
  local target="$MODEL_DIR/$1"
  if [[ -s "$target" ]]; then
    echo "déjà présent : $target"
    return
  fi
  echo "téléchargement : $remote"
  curl --fail --location --show-error --silent --output "$target.part" "$HF_BASE_URL/$remote"
  mv "$target.part" "$target"
}

fetch config.json
fetch tokenizer.json
fetch "$ONNX_FILE"

echo "Modèle prêt dans $MODEL_DIR ($ONNX_FILE)"
