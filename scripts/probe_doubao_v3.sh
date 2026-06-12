#!/usr/bin/env bash
set -euo pipefail

config_file="${1:-TTS config.md}"
endpoint="${2:-https://openspeech.bytedance.com/api/v3/tts/unidirectional}"
speaker="${3:-zh_female_vv_uranus_bigtts}"

value_from_table() {
  local label="$1"
  awk -F'|' -v label="$label" '
    index($0, "| " label " |") {
      gsub(/^[[:space:]]+|[[:space:]]+$/, "", $3)
      print $3
      exit
    }
  ' "$config_file"
}

app_id="$(value_from_table "App ID")"
access_token="$(value_from_table "Access Token")"

if [[ -z "$app_id" || -z "$access_token" ]]; then
  echo "missing App ID or Access Token in $config_file" >&2
  exit 2
fi

req_id="codex-v3-$(date +%s)"
payload="$(
  jq -nc \
    --arg speaker "$speaker" \
    '{
      user: {
        uid: "codex_v3_probe"
      },
      req_params: {
        text: "你好，这是豆包 V3 接口测试。",
        speaker: $speaker,
        audio_params: {
          format: "mp3",
          sample_rate: 24000,
          speech_rate: 0,
          enable_timestamp: false
        }
      }
    }'
)"

body_file="/tmp/doubao_v3_probe_body.txt"
meta_file="/tmp/doubao_v3_probe_meta.txt"

curl --silent --show-error --location --max-time 30 \
  --request POST \
  --header "Content-Type: application/json" \
  --header "X-Api-App-Key: ${app_id}" \
  --header "X-Api-Access-Key: ${access_token}" \
  --header "X-Api-Resource-Id: seed-tts-2.0" \
  --header "X-Api-Request-Id: ${req_id}" \
  --data "$payload" \
  --output "$body_file" \
  --write-out "http_code=%{http_code} content_type=%{content_type} size=%{size_download}\n" \
  "$endpoint" | tee "$meta_file"

echo "--- response preview ---"
set +o pipefail
sed \
  -e "s/${access_token}/[REDACTED_TOKEN]/g" \
  -e "s/${app_id}/[REDACTED_APP_ID]/g" \
  "$body_file" | head -c 800
set -o pipefail
echo
