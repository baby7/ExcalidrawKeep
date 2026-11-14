# ---------- 1. 构建前端 ----------
FROM node:20-alpine AS web-builder
WORKDIR /app

# 国内网络可传 --build-arg NPM_REGISTRY=https://registry.npmmirror.com
ARG NPM_REGISTRY=https://registry.npmjs.org

# 先整目录拷进去：package.json 的 postinstall 会跑 scripts/copy-fonts.mjs，
# 只拷 package.json 的话这一步会因为找不到脚本而失败。
COPY excalidraw-keep-web/ ./
RUN npm ci --registry=$NPM_REGISTRY --no-audit --no-fund || npm install --registry=$NPM_REGISTRY --no-audit --no-fund
RUN npm run build

# ---------- 2. 构建后端（把前端产物塞进 Spring Boot 的 static） ----------
FROM gradle:7.5.1-jdk17 AS server-builder
WORKDIR /workspace

COPY excalidraw-keep-server/ ./
COPY --from=web-builder /app/dist ./src/main/resources/static

# 用镜像里的 gradle 直接构建，避免再下载 wrapper 分发包
RUN gradle bootJar --no-daemon -x test

# ---------- 3. 运行时 ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

COPY --from=server-builder /workspace/build/libs/excalidraw-keep-server-*.jar ./app.jar

ENV TZ=Asia/Shanghai \
    APP_PORT=8080 \
    APP_DB_URL="jdbc:mysql://db:3306/excalidraw_keep?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8" \
    APP_DB_DRIVER=com.mysql.cj.jdbc.Driver \
    APP_DB_USER=excalidraw \
    APP_DB_PASSWORD=excalidraw \
    APP_DDL_AUTO=update \
    JAVA_OPTS="-Xms256m -Xmx512m"

EXPOSE 8080

# 容器内用 bash 的 /dev/tcp 探活，镜像里没有 curl
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
    CMD bash -c '</dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
