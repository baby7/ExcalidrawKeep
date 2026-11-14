# ExcalidrawKeep

把 [Excalidraw](https://github.com/excalidraw/excalidraw) 的画布保存到你自己的 MySQL 里，一个 Docker 容器起整套服务（前端 + 后端 + 数据库），不需要账号、不需要会员。

- 前端：Vite + React 18 + `@excalidraw/excalidraw` 0.18.0（自研外壳：画布列表、重命名、缩略图、手动保存按钮）
- 后端：Spring Boot 2.6.13 / Java 8 + Spring Data JPA（Hibernate）
- 数据库：MySQL 8.4（utf8mb4），表结构由 Hibernate `ddl-auto=update` 自动维护
- 部署：`Dockerfile` 三段构建 + `docker-compose.yml`（app + mysql）

## 快速开始（Docker）

```bash
cd ExcalidrawKeep
cp .env.example .env             # 可选：改端口/密码
docker compose up -d --build     # 首次会构建前端、后端镜像，等 5–10 分钟
# 浏览器打开 http://localhost:8080
```

默认账号密码只用于数据库内部，界面**免登录**。想改端口：`APP_PORT=9080 docker compose up -d`。
数据库密码建议改：`APP_DB_PASSWORD=xxx MYSQL_ROOT_PASSWORD=yyy docker compose up -d`。

国内网络构建慢时可以指定 npm 镜像（compose 里默认已经用 npmmirror）：

```bash
NPM_REGISTRY=https://registry.npmmirror.com docker compose up -d --build
```

## 本地开发

```bash
# 1. 起一个 MySQL（或用自己的）
docker run -d --name excalidraw-keep-db -p 3306:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=excalidraw_keep mysql:8.4

# 2. 后端（8080）
cd excalidraw-keep-server
APP_DB_URL='jdbc:mysql://localhost:3306/excalidraw_keep?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
APP_DB_USER=root APP_DB_PASSWORD=root ./gradlew bootRun

# 3. 前端（5173，/api 代理到 8080）
cd excalidraw-keep-web
npm install
npm run dev
```

打包成单个 jar（前端产物会进 `src/main/resources/static`）：

```bash
./scripts/build-web.sh          # 先构建前端，再 bootJar
java -jar excalidraw-keep-server/build/libs/excalidraw-keep-server-0.1.0.jar
```

## 配置项

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_PORT` | `8080` | HTTP 端口 |
| `APP_DB_URL` | `jdbc:mysql://localhost:3306/excalidraw_keep?...` | JDBC URL |
| `APP_DB_USER` / `APP_DB_PASSWORD` | `root` / `root` | 数据库账号 |
| `APP_DDL_AUTO` | `update` | `update` 自动建表；生产也可改 `validate` 手工管 |
| `APP_MAX_SCENE_CHARS` | `33554432`（32 MB） | 单次保存的 JSON 上限 |
| `APP_MAX_ATTACHMENT_BYTES` | `8388608`（8 MB） | 单个图片附件上限（Excalidraw 客户端本身限制 4 MB） |
| `APP_STATIC_LOCATION` | `classpath:/static/,file:./static/` | 前端静态资源位置 |
| `JAVA_OPTS` | `-Xms256m -Xmx512m` | JVM 参数 |

## 数据模型

| 表 | 作用 |
| --- | --- |
| `drawing` | 画布元数据：`id`/`name`/`preview`（缩略图 dataURL）/`version`（场景版本号，乐观锁）/`elements`/`app_state`（JSON 文本，LONGTEXT）/时间戳/`deleted_at`（软删） |
| `drawing_file` | 图片附件，主键 `(drawing_id, file_id)`；`file_id` 是 Excalidraw 对文件内容算的 SHA-1（40 位 hex），所以**同图天然去重、上传幂等**；`content` 为 LONGBLOB，`storage` 预留了 `s3`/`fs` 等后端 |

### 保存语义

- 前端 debounce 1s 自动保存，`PUT /api/drawings/{id}/scene` 带上当前 `baseVersion`。
- 后端用一条 CAS 语句更新：`update drawing set version = version + 1 where id = ? and version = ? and deleted_at is null`，影响 0 行即返回 **409**（带 `currentVersion`）。
- 前端收到 409 会拉最新场景，用 `reconcileElements` 合并本地改动后再重试（最多 4 次），两个标签页同时编辑不会互相覆盖。
- 只保留最新内容：每次保存直接覆盖上一版，没有历史版本；顶栏右侧「保存」按钮可手动触发，`Ctrl/Cmd + S` 同效，1s debounce 的自动保存仍然保留。
- **只有内容变了才写库**：前端用「元素 `versionNonce` 哈希 + 元素个数 + 白名单 appState」算内容指纹，只有指纹与库里最后一次保存不一致才标记为「有未保存的改动」并触发保存。滚动、缩放、选中、悬停这些也会触发 Excalidraw `onChange` 的操作不会产生新版本。
- 附件单独 `PUT /api/drawings/{id}/files/{fileId}`（body 为原始字节，`Content-Type` 即 MIME），只上传本次新增的文件；读取场景时后端把附件内联回 `data:` URL，所以导出图片/缩略图不会丢图。

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/drawings?q=&page=0&size=24` | 画布列表（不含 scene），按更新时间倒序 |
| `POST` | `/api/drawings` | 新建，body `{"name":"..."}` |
| `GET` | `/api/drawings/{id}` | 取场景（`elements`/`appState` 为原始 JSON，`files` 为 `fileId → dataURL`） |
| `PUT` | `/api/drawings/{id}/scene` | 保存，body `{"baseVersion":3,"elements":[...],"appState":{...}}`；冲突 409 |
| `PATCH` | `/api/drawings/{id}` | 改名 |
| `PUT` | `/api/drawings/{id}/preview` | 更新缩略图，body `{"preview":"data:image/png;base64,..."}` |
| `DELETE` | `/api/drawings/{id}` | 软删除（同时清理附件行） |
| `PUT` | `/api/drawings/{id}/files/{fileId}` | 上传附件，原始字节，幂等 |
| `GET` | `/actuator/health` | 健康检查 |

错误响应统一为 `{"code":"VERSION_CONFLICT","message":"...","currentVersion":4}`。

## 常见问题

- **保存超大画布报 413 / 500**：`APP_MAX_SCENE_CHARS` 与 MySQL 的 `max_allowed_packet`（compose 里设了 64M）要一起放大；Tomcat 的 `max-swallow-size` 已在 `application.yml` 放开。
- **导出的图片缺图 / 缩略图空白**：附件必须在场景 `files` 里以 `data:` URL 形式存在（后端已内联）。不要手工把它换成 `/files/...` 引用。
- **中文乱码**：数据库/表必须是 `utf8mb4`；compose 已强制 `--character-set-server=utf8mb4 --collation-server=utf8mb4_0900_ai_ci`。
- **字体加载 404**：`window.EXCALIDRAW_ASSET_PATH` 必须指向前端根路径，`public/fonts` 由 `scripts/copy-fonts.mjs` 从 npm 包里复制，构建时自动执行。
- **重新构建后浏览器还是旧界面**：静态资源一律 `Cache-Control: no-cache`（带 ETag 重新校验），强制刷新（`Ctrl/Cmd + Shift + R`）即可，不会出现旧 `index.html` 去引用已删除的 bundle。
- **想在没 Docker 的机器上试**：后端支持任意 JDBC 数据源（H2/SQLite/Oracle 都可以），换 `APP_DB_URL` 和驱动即可；DDL 由 Hibernate 生成，没有绑定 MySQL 方言。

## 后续可做

- 多人实时协作（WebSocket 中继 + 同一套 CAS 持久化）
- 用户/权限（现在免登录，设计上 `drawing` 加 `owner_id` 即可）
- 附件换 S3/OSS（`drawing_file.storage` 字段已预留）
- 分享链接（计划里留了 `share_link` 表的位置，暂未实现）
