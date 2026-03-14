#!/bin/bash
# ~/bin/에 스크립트 심볼릭 링크 생성
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p ~/bin
for f in ai-copy ai-write ai-patch ai-push ai-build-local ai-build-remote ai-status; do
    [ -f "$SCRIPT_DIR/$f" ] && ln -sf "$SCRIPT_DIR/$f" ~/bin/$f
done
echo "✅ 설치 완료: ~/bin/에 링크 생성됨"
ls -la ~/bin/ai-*
