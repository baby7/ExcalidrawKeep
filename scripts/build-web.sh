#!/usr/bin/env bash
# 构建前端 → 复制进后端 static → 打 fat jar
set -euo pipefail

cd "$(dirname "$0")/.."

echo "=== 1/3 构建前端 ==="
cd excalidraw-keep-web
if [ ! -d node_modules ]; then
  npm install --registry="${NPM_REGISTRY:-https://registry.npmmirror.com}"
fi
npm run build
cd ..

echo "=== 2/3 复制 dist 到后端静态资源 ==="
rm -rf excalidraw-keep-server/src/main/resources/static
mkdir -p excalidraw-keep-server/src/main/resources/static
cp -r excalidraw-keep-web/dist/. excalidraw-keep-server/src/main/resources/static/

echo "=== 3/3 打 jar ==="
cd excalidraw-keep-server
./gradlew bootJar

echo
echo "完成：excalidraw-keep-server/build/libs/"
