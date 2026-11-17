#!/usr/bin/env bash
# 不用 docker compose 的部署脚本。
#
# 模式一：自带 MySQL（会建网络 + 起 db 容器）
#   bash scripts/docker-run.sh
#   APP_IMAGE=baby7/excalidraw-keep:0.1.1 APP_PORT=9080 bash scripts/docker-run.sh
#
# 模式二：复用已在跑的 MySQL，按 IP 连接（不建网络、不起 db 容器）
#   APP_DB_URL='jdbc:mysql://192.168.3.11:3306/excalidraw_keep?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8' \
#   APP_DB_USER=root APP_DB_PASSWORD=yourpass \
#   bash scripts/docker-run.sh
#
# 重复执行是安全的：模式一下网络/数据库容器复用、app 容器重建；数据库数据在命名卷里。
set -euo pipefail

IMAGE="${APP_IMAGE:-baby7/excalidraw-keep:latest}"
APP_NAME="${APP_CONTAINER:-excalidraw-keep}"
PORT="${APP_PORT:-8080}"
DB_USER="${APP_DB_USER:-excalidraw}"
DB_PASS="${APP_DB_PASSWORD:-excalidraw}"

if ! command -v docker >/dev/null 2>&1; then
  echo "没有找到 docker 命令" >&2
  exit 1
fi

NET_ARGS=()

if [ -n "${APP_DB_URL:-}" ]; then
  # ---------- 模式二：外部数据库，按 IP 直连 ----------
  DB_URL="$APP_DB_URL"
  echo "使用外部数据库：${DB_URL%%\?*}"
  echo "（不创建 Docker 网络，走默认 bridge，直接访问该 IP）"
else
  # ---------- 模式一：自带 MySQL ----------
  NET="${DOCKER_NET:-excalidraw-keep-net}"
  DB_NAME="${DB_CONTAINER:-excalidraw-keep-db}"
  ROOT_PASS="${MYSQL_ROOT_PASSWORD:-rootpass}"
  DB_URL="jdbc:mysql://${DB_NAME}:3306/excalidraw_keep?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8"
  NET_ARGS=(--network "$NET")

  # 1) 容器网络：MySQL 不发布端口，只让 app 通过容器名访问
  docker network inspect "$NET" >/dev/null 2>&1 || docker network create "$NET"

  # 2) MySQL
  if ! docker container inspect "$DB_NAME" >/dev/null 2>&1; then
    echo "创建 MySQL 容器 $DB_NAME"
    docker run -d --name "$DB_NAME" --network "$NET" --restart unless-stopped \
      -e MYSQL_ROOT_PASSWORD="$ROOT_PASS" \
      -e MYSQL_DATABASE=excalidraw_keep \
      -e MYSQL_USER="$DB_USER" \
      -e MYSQL_PASSWORD="$DB_PASS" \
      -e TZ=Asia/Shanghai \
      -v "${DB_NAME}-data:/var/lib/mysql" \
      mysql:8.4 \
      --character-set-server=utf8mb4 \
      --collation-server=utf8mb4_0900_ai_ci \
      --max-allowed-packet=64M >/dev/null
  else
    docker start "$DB_NAME" >/dev/null 2>&1 || true
  fi

  # 3) 等 MySQL 真正可连接（首次初始化要 20 秒左右）
  printf '等待 MySQL'
  for i in $(seq 1 60); do
    if docker exec "$DB_NAME" mysqladmin ping -h 127.0.0.1 -u"$DB_USER" -p"$DB_PASS" --silent >/dev/null 2>&1; then
      echo " 就绪"
      break
    fi
    printf '.'
    sleep 2
    if [ "$i" -eq 60 ]; then
      echo
      echo "MySQL 没起来，看日志：docker logs $DB_NAME" >&2
      exit 1
    fi
  done
fi

# 4) app（旧容器直接删掉重建，数据都在数据库里，不受影响）
docker rm -f "$APP_NAME" >/dev/null 2>&1 || true

docker run -d --name "$APP_NAME" ${NET_ARGS[@]+"${NET_ARGS[@]}"} --restart unless-stopped \
  -p "${PORT}:8080" \
  -e TZ=Asia/Shanghai \
  -e APP_PORT=8080 \
  -e APP_DB_URL="$DB_URL" \
  -e APP_DB_DRIVER=com.mysql.cj.jdbc.Driver \
  -e APP_DB_USER="$DB_USER" \
  -e APP_DB_PASSWORD="$DB_PASS" \
  -e APP_DDL_AUTO="${APP_DDL_AUTO:-update}" \
  -e APP_MAX_SCENE_CHARS="${APP_MAX_SCENE_CHARS:-33554432}" \
  -e APP_MAX_ATTACHMENT_BYTES="${APP_MAX_ATTACHMENT_BYTES:-8388608}" \
  -e JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m}" \
  --health-cmd "bash -c '</dev/tcp/127.0.0.1/8080' || exit 1" \
  --health-interval 30s \
  --health-timeout 5s \
  --health-retries 3 \
  --health-start-period 40s \
  "$IMAGE" >/dev/null

echo
echo "已启动："
echo "  应用   http://localhost:${PORT}"
echo "  健康   http://localhost:${PORT}/actuator/health"
echo "  日志   docker logs -f ${APP_NAME}"
echo "  状态   docker inspect --format '{{.State.Health.Status}}' ${APP_NAME}"
