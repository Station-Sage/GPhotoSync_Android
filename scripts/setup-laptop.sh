#!/bin/bash
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p ~/bin
ln -sf "$SCRIPT_DIR/ai-copy-win" ~/bin/ai-copy
for f in ai-read ai-err ai-diff; do
    [ -f "$SCRIPT_DIR/$f" ] && ln -sf "$SCRIPT_DIR/$f" ~/bin/$f
done
grep -q 'HOME/bin' ~/.bashrc || echo 'export PATH="$HOME/bin:$PATH"' >> ~/.bashrc
echo "✅ 랩탑 스크립트 설치 완료"
ls -la ~/bin/ai-*
