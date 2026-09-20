#!/usr/bin/env bash
# ===========================================================
# encrypt-secrets.sh
# GMOコイン API KEY / SECRET を暗号化して secret.properties に書き込む
#
# 使用方法:
#   export CRYPTO_MASTER_KEY="your_strong_master_password"
#   bash scripts/encrypt-secrets.sh <API_KEY> <API_SECRET>
#
# 前提:
#   - java / mvn がインストール済みであること
#   - 事前に mvn package でビルドしておくこと
# ===========================================================

set -euo pipefail

# --- 引数チェック ---
if [ "$#" -ne 2 ]; then
  echo "使用方法: bash scripts/encrypt-secrets.sh <API_KEY> <API_SECRET>" >&2
  exit 1
fi

API_KEY="$1"
API_SECRET="$2"

# --- 環境変数チェック ---
if [ -z "${CRYPTO_MASTER_KEY:-}" ]; then
  echo "エラー: 環境変数 CRYPTO_MASTER_KEY が未設定です。" >&2
  echo "  export CRYPTO_MASTER_KEY='your_strong_master_password'" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
SECRET_PROPS="$PROJECT_DIR/src/main/resources/secret.properties"
JAR_PATH="$PROJECT_DIR/target/crypto-bot2-1.0.0.jar"

# --- JAR 存在確認 ---
if [ ! -f "$JAR_PATH" ]; then
  echo "エラー: JAR ファイルが見つかりません: $JAR_PATH" >&2
  echo "  先に 'mvn clean package -DskipTests' を実行してください。" >&2
  exit 1
fi

echo "API KEY と SECRET を暗号化しています..."

# Java の EncryptionService を使って暗号化
ENC_KEY=$(java \
  -DCRYPTO_MASTER_KEY="$CRYPTO_MASTER_KEY" \
  -cp "$JAR_PATH" \
  com.example.cryptobot2.util.EncryptCli \
  "$API_KEY" 2>/dev/null)
echo " SECRET を暗号化しています..."

ENC_SECRET=$(java \
  -DCRYPTO_MASTER_KEY="$CRYPTO_MASTER_KEY" \
  -cp "$JAR_PATH" \
  com.example.cryptobot2.util.EncryptCli \
  "$API_SECRET" 2>/dev/null)

echo "secret.properties を書き直ししています..."

# --- secret.properties に書き込む ---
cat > "$SECRET_PROPS" << EOF
# ============================================================
# secret.properties — 暗号化済み API KEY/SECRET
# ★ このファイルは .gitignore で Git 管理から除外すること
# ★ 生成日時: $(date '+%Y-%m-%d %H:%M:%S')
# ============================================================

gmo.api.key=${ENC_KEY}
gmo.api.secret=${ENC_SECRET}
EOF
echo "secret.properties を書き直し終了."

echo "secret.properties を更新しました: $SECRET_PROPS"
echo "注意: このファイルは Git にコミットしないでください。"
