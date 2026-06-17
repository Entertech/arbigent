#!/usr/bin/env bash
# Start a local OpenAI-compatible vision endpoint (mlx-vlm) so arbigent can use a
# self-hosted Qwen3-VL grounder with NO code changes — just point the existing
# OpenAI provider at it. Verified on Apple Silicon (M5 Pro / 48GB), 2026-06-17.
#
#   ./scripts/local-vl-server.sh                 # Qwen3-VL-8B-4bit on :8080 (default)
#   MODEL=<hf-or-local-path> PORT=8080 ./scripts/local-vl-server.sh
#
# Then run arbigent against it:
#   M=mlx-community/Qwen3-VL-8B-Instruct-4bit   # (or the local cache path)
#   ./arbigent-cli/build/install/arbigent/bin/arbigent run \
#     --project-file=<project>.yaml \
#     --ai-type=openai \
#     --openai-endpoint=http://localhost:8080/v1/ \
#     --openai-model-name="$M" \
#     --openai-api-key=dummy-local \
#     --scenario-ids="<id>"
#
# Notes:
# - Download weights via ModelScope (China-direct, no proxy) instead of HF:
#     pip install -U modelscope
#     python -c "from modelscope import snapshot_download as d; print(d('mlx-community/Qwen3-VL-8B-Instruct-4bit'))"
#   Pass the printed local path as MODEL to avoid an HF round-trip.
# - Qwen3.6 unified-multimodal (mlx-community/Qwen3.6-35B-A3B-4bit) also works but
#   ONLY via mlx-vlm>=0.6.0 (LM Studio / Ollama vision paths are still broken for it).
# - Stop the server: pkill -f mlx_vlm.server
set -euo pipefail

MODEL="${MODEL:-mlx-community/Qwen3-VL-8B-Instruct-4bit}"
PORT="${PORT:-8080}"
HOST="${HOST:-127.0.0.1}"

if ! python3 -c "import mlx_vlm" 2>/dev/null; then
  echo "mlx-vlm not found. Install with: pip install -U mlx-vlm" >&2
  exit 1
fi

echo "Starting mlx-vlm OpenAI-compatible server"
echo "  model: $MODEL"
echo "  endpoint: http://$HOST:$PORT/v1/  (chat/completions, image_url supported)"
exec python3 -m mlx_vlm.server --model "$MODEL" --host "$HOST" --port "$PORT" --log-level WARNING
