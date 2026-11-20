#!/usr/bin/env bash
# 升版本号：
#   - versionCode 一律 +1（Android 判断"哪个更新"，绝不可回退）
#   - versionName 默认把最后一段 +1（3.7 -> 3.8），也可直接指定（如 ./tools/bump-version.sh 4.0）
#   - 同时向 CHANGELOG.md 插入新版本小节 —— assembleRelease 的 verifyVersion 门禁要求，
#     否则升完版本会直接构建失败
#
# 用法：
#   ./tools/bump-version.sh             # 3.7 -> 3.8，code+1
#   ./tools/bump-version.sh 4.0         # 指定 4.0，code+1
#   ./tools/bump-version.sh 3.8 "修复 x；新增 y"
#
# 规矩：每次重新出 APK 前先跑一次。界面标题栏副标题读 versionName，
# 安装包清单读 versionCode，两者必须与实际改动一致，否则装了两版也分不清先后。
set -euo pipefail

cd "$(dirname "$0")/.."
FILE=app/build.gradle.kts
CHANGELOG=CHANGELOG.md
[ -f "$FILE" ] || { echo "找不到 $FILE（请在项目里 tools/ 下运行本脚本）" >&2; exit 1; }
[ -f "$CHANGELOG" ] || { echo "找不到 $CHANGELOG" >&2; exit 1; }

OLD_CODE=$(grep -E '^[[:space:]]*versionCode = ' "$FILE" | head -1 | tr -cd '0-9')
OLD_NAME=$(grep -E '^[[:space:]]*versionName = "' "$FILE" | head -1 | sed -E 's/.*"([^"]*)".*/\1/')
[ -n "$OLD_CODE" ] && [ -n "$OLD_NAME" ] || { echo "解析版本号失败：$FILE" >&2; exit 1; }

if [ $# -ge 1 ]; then
  NEW_NAME="$1"
else
  IFS='.' read -r -a parts <<< "$OLD_NAME"
  last=$(( ${parts[${#parts[@]}-1]} + 1 ))
  parts[${#parts[@]}-1]=$last
  NEW_NAME=$(IFS='.'; echo "${parts[*]}")
fi
NEW_CODE=$((OLD_CODE + 1))

# 版本号格式校验（避免把奇怪字符串写进 Gradle）
if ! printf '%s' "$NEW_NAME" | grep -Eq '^[0-9]+\.[0-9]+(\.[0-9]+)?$'; then
  echo "版本号格式不正确：$NEW_NAME（应形如 3.8 或 3.8.1）" >&2
  exit 1
fi
if grep -q "## $NEW_NAME" "$CHANGELOG"; then
  echo "CHANGELOG.md 里已有 $NEW_NAME 小节，拒绝重复升版" >&2
  exit 1
fi

SUMMARY="${2:-（待补充本次变更）}"

sed -i -E "s/(^[[:space:]]*versionCode = )[0-9]+/\1${NEW_CODE}/" "$FILE"
sed -i -E "s/(^[[:space:]]*versionName = \")[^\"]*/\1${NEW_NAME}/" "$FILE"

# 在 CHANGELOG 的第一个 "## " 之前插入新小节（保留原有说明文字）
TMP="$(mktemp)"
awk -v ver="$NEW_NAME" -v summary="$SUMMARY" '
  !done && /^## / {
    print "## " ver; print ""; print "- " summary; print ""
    done = 1
  }
  { print }
' "$CHANGELOG" > "$TMP"
mv "$TMP" "$CHANGELOG"

echo "versionCode: ${OLD_CODE} -> ${NEW_CODE}"
echo "versionName: ${OLD_NAME} -> ${NEW_NAME}"
echo "CHANGELOG.md 已插入「## $NEW_NAME」小节，请补写详细内容。"
