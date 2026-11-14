# ExcalidrawKeep 设计方案

> 目标：一个 `docker compose up` 就能用的 Excalidraw 私有化部署方案，画布数据落到 MySQL，
> 后端 Spring Boot，未来可平滑地换/加 MongoDB。

> **实现状态（2026-11-14 更新）**：本文档是立项时的方案推演，实际实现已按用户选型落地，
> 与下文有几处差异，**以代码与 `README.md` 为准**：
> - 后端是 **Gradle + Spring Boot 2.6.13 + Java 8**（阿里云 Spring Initializr 初始化），不是 Maven，也没有用 Flyway → 改用 Hibernate `ddl-auto=update`（可移植到 H2/SQLite/Oracle）。
> - 用户模型：**免登录**（下文"用户/权限"部分属于后续可做项）；画布从列表页**新标签页打开**。
> - 历史版本：**按需求已移除**——只保存最新内容（每次保存覆盖上一版）；顶栏右侧提供「保存」按钮，Ctrl/Cmd+S 同效。
> - 分享链接（`share_link`）暂不实现；Mongo 只留结构口子（关系库 + `drawing_file.storage` 字段）。
> - 许可证：MIT。

---

## 0. 结论先行

| 决策点 | 结论 | 理由（一句话） |
|---|---|---|
| 前端形态 | **自研 React 外壳**（Vite + React 18 + `@excalidraw/excalidraw` 0.18.x） | 只有外壳才拿得到"画布列表/缩略图/分享"的控制权；官方 excalidraw.com 壳的后端是密文存储，服务端无法索引 |
| 画布存储 | 一个场景 = 一行；`elements`/`appState` 存 JSON 文本 | 画布是"整读整写"的文档，不是关系型数据；拆成节点表既无查询需求又拖慢保存 |
| 附件（图片） | 独立表 + 内容寻址（fileId = SHA-1） | 和 Excalidraw 内部语义一致，天然幂等/去重，场景 PUT 从 MB 级降到 KB 级 |
| 并发 | 乐观锁 `version` + HTTP 409 + 客户端合并重试 | 简单、够用；不需要悲观锁/WebSocket |
| 打包 | 单容器：Spring Boot 同时提供 `/api/**` 与 SPA 静态资源 | 同源、无 CORS、部署只有一个镜像 + 一个 DB |
| 数据库扩展 | 元数据留在关系库；只把"大块内容/附件"抽象成 `BlobStore` 端口 | 一开始就做"整库双实现"等于双倍测试成本，收益为 0 |
| 协作（实时） | **MVP 不做**，留接口 | 协作要 Yjs 或官方 socket 协议 + 房间持久化，复杂度是画布 CRUD 的 3 倍 |

MVP 体量估算：后端 ~1500 行 Java，前端 ~800 行 TSX，5–8 个工作日能到"可用"。

---

## 1. 调研结论：为什么是这三条路里的一条

### 1.1 官方 excalidraw 仓库其实是三个东西

`excalidraw/` 这个 monorepo 包含：

1. `packages/excalidraw` —— **可嵌入的 React 组件**（`@excalidraw/excalidraw` 0.18.0，MIT）。
   这是我们要用的东西：`<Excalidraw />` + `onChange` + imperative API。
2. `excalidraw-app` —— excalidraw.com 那个网站的外壳，含账号、分享链接、协作房间。
3. 后端只有三个，而且都不是"画布库"：
   - `json.excalidraw.com`：只存**加密后的不透明密文**；
   - Firebase：协作房间持久化 + 分享里的图片；
   - socket.io 服务器：明文/密文中继。

关键证据：分享链接是纯前端加密后上传的（`excalidraw/excalidraw-app/data/index.ts:248` `exportToBackend`：
`generateEncryptionKey` → `compressData` → POST 二进制 → 拿回 `{id}` → URL 变成 `#json=id,key`）。
服务端拿到的东西**解不开**，所以官方后端天生做不了"列表 / 缩略图 / 全文搜索 / 团队共享"。
这正是我们要自建外壳的根本原因。

### 1.2 excalidraw-full（Go）走的是" MITM 官方前端"路线

`excalidraw-full/` 的做法很聪明但很脆：

- `frontend.patch` 只有 40 行，**全是 `.env.production`**：把 `VITE_APP_BACKEND_V2_GET/POST`、
  `LIBRARY_BACKEND`、`WS_SERVER_URL` 指到 `localhost:3002`，把 Firebase 的
  `authDomain`/`databaseURL` 置空，关掉埋点。
- `main.go` 负责"圆谎"：
  - 返回 JS 时把 `firestore.googleapis.com` 字符串替换成自己的域名，并把 `ssl=!0` 改成 `0`
    （否则浏览器拿 http 地址去连会失败）；
  - 实现 `/v1/projects/{p}/databases/{d}/documents:commit|batchGet` 伪 Firestore；
  - 实现 socket.io 事件 `init-room`/`join-room`/`server-broadcast` 等；
  - 顺带加了 `/api/v2/kv/{key}` 这个"画布 CRUD"接口（需 JWT）。

代价：**它的前端是编译死的**。想加一个"我的画布"列表页、想换缩略图逻辑，都得改官方源码再重新打包；
而且伪 Firestore 和 socket 协议随上游改动就会坏。参考价值高，作为产品路线不划算。

### 1.3 ExcaliDash 是最接近的架构（但后端是 Node）

`ExcaliDash/` = 自研 React 前端 + Express/Prisma 后端，功能最全（集合、权限、分享、快照、协作）。
可以直接抄的设计（**不要抄代码**，它是 LGPL-3.0）：

| 设计点 | 位置 | 我们怎么用 |
|---|---|---|
| 场景行带 `version`，冲突返 409 | `ExcaliDash/backend/src/routes/dashboard/sceneUpdate.ts:38` | 照搬语义 |
| 保存 debounce 1000ms | `ExcaliDash/frontend/src/pages/editor/useEditorPersistence.ts:432` | 照搬时长 |
| 409 → 重新拉取 → `reconcileElements` 合并 → 重试 | 同上 `:248-260`，最多 4 次指数退避 | 照搬策略，次数降到 3 |
| appState 白名单（只存背景色/网格） | `ExcaliDash/frontend/src/pages/editor/shared.ts:81` | 照搬，字段再加 `theme` |
| 附件内容寻址 + 场景里存引用 | `ExcaliDash/frontend/src/pages/editor/shared.ts:300` | 照搬，但我们**存引用不返引用**（见 §4.4） |
| 字体本地化 + `EXCALIDRAW_ASSET_PATH` | `ExcaliDash/frontend/index.html:15-22`、`scripts/copy-excalidraw-assets.mjs` | 照搬 |
| 服务端合并 files 并集 | `sceneUpdate.ts:18` `mergeFilesUnion` | **不需要**——我们把附件拆成独立接口后，场景 PUT 里没有 files |

ExcaliDash 的 `Drawing`/`DrawingSnapshot`/`DrawingFile` 三个模型（`ExcaliDash/backend/prisma/schema.prisma:96`/`:119`/`:274`）
基本就是我们要的表结构，稍微简化即可。

### 1.4 三条路线对比

| | 自研外壳（**选它**） | MITM 官方前端（excalidraw-full） | fork 官方 monorepo |
|---|---|---|---|
| 画布列表/缩略图/分享 | 完全自由 | 受限于编译死的 bundle | 自由但改动面大 |
| 升级上游 | 只升 npm 包，改 1 个适配层 | 每次重打补丁、重验证伪协议 | 大版本 merge 冲突 |
| 实时协作 | 自己做（可选） | 现成（但要维持协议兼容） | 现成 |
| 工作量（MVP） | 中 | 低（但天花板低） | 高 |
| 长期可维护性 | 高 | 低 | 中 |

---

## 2. 总体架构

```
┌────────────────────────── 浏览器 ──────────────────────────┐
│  React 18 + Vite                                           │
│  ├── /            画布列表（缩略图、搜索、新建、删除）        │
│  ├── /d/:id       编辑器（<Excalidraw /> 全屏）              │
│  └── /s/:token    只读/可编辑分享页                          │
└───────────────────────────┬────────────────────────────────┘
                            │ 同源 HTTP/JSON + 原始字节
┌───────────────────────────▼────────────────────────────────┐
│ Spring Boot 3.5 / Java 21（单容器，端口 8080）               │
│  ├── web/        SceneController · FileController · ShareController │
│  ├── service/    SceneService（CAS 保存）· FileService · PreviewService │
│  ├── store/      SceneRepository ──► JPA/MySQL               │
│  │               BlobStore ────────► jdbc | fs | (mongo)     │
│  └── static/     SPA dist + SPA fallback + 字体              │
└───────────────────────────┬────────────────────────────────┘
                            │ JDBC
                    ┌───────▼────────┐
                    │  MySQL 8.4     │  外部实例或 compose 里的 mysql
                    └────────────────┘
```

要点：

- **同源**：前端 `fetch("/api/v1/...")` 即可，不用配 CORS，也不用暴露两个端口。
- **开发期**：Vite `server.proxy` 把 `/api` 转发到 `localhost:8080`，前后端各自热更新。
- **单容器**：`frontend/dist` 在 Docker 构建阶段打进镜像，Spring `spring.web.resources.static-locations`
  指向它，并注册 SPA fallback（除 `/api/**`、`/assets/**`、`/fonts/**` 外都返回 `index.html`）。

---

## 3. 数据模型（MySQL 8）

`ExcalidrawKeep/backend/src/main/resources/db/migration/V1__init.sql`（Flyway）：

```sql
CREATE TABLE drawing (
  id          CHAR(36)      NOT NULL PRIMARY KEY,
  name        VARCHAR(255)  NOT NULL,
  elements    LONGTEXT      NOT NULL,                       -- JSON 数组
  app_state   LONGTEXT      NOT NULL,                       -- JSON 对象（白名单字段）
  preview     MEDIUMTEXT    NULL,                           -- data:image/webp;base64,...
  version     INT           NOT NULL DEFAULT 0,
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at  DATETIME(3)   NULL,
  KEY idx_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE drawing_file (
  drawing_id  CHAR(36)     NOT NULL,
  file_id     CHAR(40)     NOT NULL,          -- Excalidraw 的内容哈希（SHA-1 hex）
  mime_type   VARCHAR(128) NOT NULL,
  size_bytes  BIGINT       NOT NULL,
  content     LONGBLOB     NULL,              -- storage='db' 时有效
  storage     VARCHAR(16)  NOT NULL DEFAULT 'db',   -- db | fs | s3 | mongo
  storage_key VARCHAR(512) NULL,              -- storage!='db' 时有效
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (drawing_id, file_id),
  CONSTRAINT fk_file_drawing FOREIGN KEY (drawing_id) REFERENCES drawing(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE drawing_snapshot (      -- 暂未实现：当前只保留最新内容，本节保留作未来启用时的结构参考
  id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  drawing_id CHAR(36)     NOT NULL,
  version    INT          NOT NULL,
  elements   LONGTEXT     NOT NULL,
  app_state  LONGTEXT     NOT NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_drawing_version (drawing_id, version),
  CONSTRAINT fk_snap_drawing FOREIGN KEY (drawing_id) REFERENCES drawing(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE share_link (
  token_hash CHAR(64)     NOT NULL PRIMARY KEY,   -- SHA-256(token)，库里不存明文
  drawing_id CHAR(36)     NOT NULL,
  mode       VARCHAR(8)   NOT NULL,               -- view | edit
  expires_at DATETIME(3)  NULL,
  revoked_at DATETIME(3)  NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_share_drawing (drawing_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

设计说明：

- **为什么 `elements` 是一列 JSON 而不是 `element` 表**：画布永远是整读整写，没人会
  `WHERE element.type='rectangle'`；拆表后一次保存要 upsert 几百行，纯亏。真要"按文字搜画布"，
  以后加一张 `drawing_text(drawing_id, text)` 冗余索引用 MySQL FULLTEXT 或 ES，不破坏主模型。
- **`app_state` 只存白名单**：`viewBackgroundColor`、`gridSize`、`gridStep`、`gridModeEnabled`、`theme`。
  绝不能存 `scrollX/scrollY/width/height`——否则换个屏幕打开就跳到奇怪的位置，而且这些字段每次滚动都变，
  会把"有变更"判断搞乱。
- **`version` 从 0 开始，创建时就是 0**，第一次保存 → 1；409 的语义更自然。
- **软删**：`deleted_at`，回收站以后再做（先不做物理删除，便于误删恢复）。
- **`file_id` 是 Excalidraw 自己算的 SHA-1**（`excalidraw/packages/excalidraw/data/blob.ts:263`
  `generateIdFromFile`，40 位 hex），所以同一张图在同一个画布里天然只有一条记录。注意：它是
  **按画布隔离**的（主键含 `drawing_id`），不跨画布去重——跨画布共享会引入引用计数和"删了别人的图"的坑，
  MVP 不做。真想省空间，以后加 `blob(content_hash, content)` + `drawing_file.blob_id` 两级结构即可。

---

## 4. 后端设计

### 4.1 API

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/v1/drawings?cursor=&limit=24&q=` | 列表；**不返回** `elements` |
| `POST` | `/api/v1/drawings` | 新建 `{name?, elements?, appState?}` → `{id, version:0}` |
| `GET` | `/api/v1/drawings/{id}` | 全量场景 `{id,name,version,elements,appState,files:{fileId:dataURL},updatedAt}` |
| `PUT` | `/api/v1/drawings/{id}` | 保存 `{baseVersion, elements, appState}` → `{version}` / `409 {currentVersion}` |
| `PATCH` | `/api/v1/drawings/{id}` | 改名 `{name}` |
| `DELETE` | `/api/v1/drawings/{id}` | 软删 |
| `PUT` | `/api/v1/drawings/{id}/files/{fileId}` | 原始字节上传，`Content-Type` 必填，幂等（存在即 200） |
| `GET` | `/api/v1/drawings/{id}/files/{fileId}` | 原始字节，`ETag` + `Cache-Control: immutable`（内容寻址，永不变） |
| `PUT` | `/api/v1/drawings/{id}/preview` | 缩略图（webp dataURL 或二进制） |
| `POST` | `/api/v1/drawings/{id}/snapshots` | 手动打点（可选，当前未实现） |
| `GET` | `/api/v1/drawings/{id}/export` | 下载 `.excalidraw` |
| `POST` | `/api/v1/share` / `GET /api/v1/share/{token}` | 分享链接（M5） |

统一错误体：`{"code":"VERSION_CONFLICT","message":"...","details":{...}}`。

### 4.2 保存语义（核心）

```java
@Transactional
public int save(String id, int baseVersion, String elementsJson, String appStateJson) {
    int rows = jdbc.update("""
        UPDATE drawing SET elements = ?, app_state = ?, version = version + 1
        WHERE id = ? AND version = ? AND deleted_at IS NULL
        """, elementsJson, appStateJson, id, baseVersion);
    if (rows == 0) throw new VersionConflictException(currentVersionOf(id));
    return baseVersion + 1;
}
```

为什么用 `UPDATE ... WHERE version = ?` 而不是先 SELECT 再 UPDATE：一条语句原子化，不用锁，不用事务隔离级别调优。

两个必须做的"防炸"：

1. **JSON 长度校验**：`APP_MAX_SCENE_BYTES`（默认 32MB），超了返回 413 并给前端友好提示
   （提示用户"图太多，试试把图片压缩后重新粘贴"）。
2. **Tomcat/Jackson 配置**（默认值会静默 400，非常坑）：
   - `server.tomcat.max-http-form-post-size: 64MB`（默认 2MB，只影响表单，但我们用 JSON body 不受此限——真正卡的是下面两条）
   - `spring.servlet.multipart.max-file-size: 64MB`（附件上传若走 multipart）
   - `spring.jackson` 侧：Jackson 2.15+ 默认 `maxStringLength` 20MB，需要
     `JsonFactory` 配置 `StreamReadConstraints.maxStringLength(64MB)`，否则大画布直接抛
     `StreamConstraintsException`。

### 4.3 加载语义

`GET /drawings/{id}` 一次返回场景 + 附件。附件有两种给法：

- **A. 服务端拼成 dataURL**（`files: {fileId: "data:image/png;base64,..."}`）：前端零改动，
  `exportScene`/缩略图直接就能用。缺点：每次打开都把这几十 MB 图片 base64 传一遍。
- **B. 返回引用**（`files: {fileId: "/api/v1/drawings/xx/files/abc"}`）：加载快，但
  **导出的 SVG/PNG 会丢图**——Excalidraw 导出时按 `fileId` 在 files map 里取 dataURL 去画，
  拿到一个 URL 字符串它不会去 fetch。

**MVP 选 A**（简单、正确优先），并在响应里加 `ETag`/`If-None-Match` 让浏览器缓存整份场景。
等有人抱怨"打开 3 秒"时，再切 B + 导出前 `rehydrate`（ExcaliDash 走的就是这条路，
见 `ExcaliDash/frontend/src/utils/rehydrateFiles.ts`）。

### 4.4 附件上传流程（前端）

```ts
// 保存前：把 scene 里 dataURL 的 fileId 找出来，逐个上传（内容寻址，重复上传无副作用）
async function uploadNewFiles(api: ExcalidrawAPI) {
  const files = api.getFiles();                        // {fileId: {dataURL, mimeType, id, created}}
  const known = new Set(knownFileIds);                 // 已经上传过的 fileId
  for (const [id, f] of Object.entries(files)) {
    if (known.has(id) || !f.dataURL?.startsWith("data:")) continue;
    await fetch(`/api/v1/drawings/${drawingId}/files/${id}`, {
      method: "PUT", body: await (await fetch(f.dataURL)).blob(),
      headers: { "Content-Type": f.mimeType },
    });
    known.add(id);
  }
}
```

- 上传成功后再 PUT 场景，避免"场景引用了不存在的附件"。
- 场景里**保留 dataURL 原样**（不替换成 URL），所以前后端都不用做 rehydrate。
- 删除图片元素时不删 `drawing_file` 行 → 撤销 (Ctrl+Z) 还能把图找回来；清理交给"画布被删"的
  `ON DELETE CASCADE` 或一个每周的孤儿清理任务。

### 4.5 存储抽象（为 MongoDB 留的口子）

只抽象两层，别抽象整库：

```java
public interface BlobStore {                       // 附件字节
  void put(String drawingId, String fileId, String mimeType, byte[] bytes);
  Optional<byte[]> get(String drawingId, String fileId);
  void delete(String drawingId, String fileId);
}

public interface SceneRepository {                 // 场景文档
  Scene get(String id);
  int saveCas(String id, int baseVersion, Scene scene);  // 返回新 version
  Slice<DrawingSummary> list(String cursor, int limit, String q);
}
```

- `JdbcBlobStore`（默认）、`FilesystemBlobStore`（`APP_STORAGE_BLOB=fs` + 挂载卷，适合几 GB 图片）。
- `JdbcSceneRepository` 用 JPA/JdbcTemplate；未来 `MongoSceneRepository` 加
  `@ConditionalOnProperty(name="app.storage.scene", havingValue="mongo")`，切库只需要改环境变量。
  **但要诚实**：真到那天，列表查询、排序、软删、快照都要在 Mongo 侧重写一遍，这是"双份维护"，
  只有在确实要处理"单画布几万元素 + 全文检索"时才值得。

---

## 5. 前端设计

### 5.1 依赖

```json
{
  "react": "18.3.1",
  "react-dom": "18.3.1",
  "@excalidraw/excalidraw": "0.18.0",
  "react-router-dom": "^7",
  "vite": "^6",
  "typescript": "^5"
}
```

`@excalidraw/excalidraw` **锁小版本**（`0.18.0`，不加 `^`）。这个包 API 漂移很快：
0.18.0 里 `exportToBlob`/`exportToSvg` 已经**不再从包根导出**，统一成
`exportScene({ data, type: "png" | "svg" | "canvas" | "jpeg" })`（`excalidraw/packages/excalidraw/index.tsx:441`），
而且 `reconcileElements` 的参数顺序也变过（现在是 `(localElements, remoteElements, localAppState)`，
`excalidraw/packages/excalidraw/data/reconcile.ts:73`）。写一个 `src/editor/excalidraw-adapter.ts`
把所有这类调用收敛到一处。

### 5.2 编辑器页

```tsx
<div style={{ height: "100dvh" }}>
  <Excalidraw
    excalidrawAPI={(a) => { apiRef.current = a; }}
    initialData={initialScene}            // 支持返回 Promise 的函数，先 await GET /drawings/{id}
    onChange={handleChange}
    langCode="zh-CN"
    viewModeEnabled={readonly}
    isCollaborating={false}
    UIOptions={{ canvasActions: { loadScene: false, saveToActiveFile: false } }}
    renderTopRightUI={() => <SaveIndicator state={saveState} />}
  />
</div>
```

- `loadScene: false` 去掉"打开文件"（用户从本地导入的入口放到列表页，导入后落库）；
  导出功能保留（`UIOptions.canvasActions.export` 默认开）。
- 保存状态指示器（已保存/保存中/冲突）是**必须的**，否则用户不知道数据到底存没存。

### 5.3 保存管线

```
onChange(elements, appState, files)
  ├─ 忽略：viewMode、version 没变（getSceneVersion 相同 → 纯滚动/选中）
  ├─ appState → 白名单过滤
  ├─ 标记 dirty；debounce 1000ms（maxWait 5000ms），页面隐藏时立即 flush
  └─ 串行执行器（保证同一时刻只有一个 PUT 在飞）：
       1. uploadNewFiles()                       // 附件先落地
       2. PUT /drawings/{id} {baseVersion, elements, appState}
       3. 200 → version = newVersion，dirty = false
          409 → GET 场景 → reconcileElements(local, remote, appState)
                → updateScene(merged) → 重试（≤3 次，200/400/800ms 退避）
          其它 → 指数退避重试，失败 3 次后弹"未保存"横幅
```

细节：

- `pagehide` / `visibilitychange=hidden` 时用 `navigator.sendBeacon` 或
  `fetch(url, { keepalive: true })` 做最后一次抢救——但 **keepalive 请求体上限只有 64KB**，
  大画布兜不住，所以平时必须按时 flush，不能指望它。
- 冲突合并只对 `elements` 做；`appState` 一律以服务端为准（本地只保留视图类字段）。
  合并后调 `excalidrawAPI.updateScene({ elements })` 而不是重挂组件，否则会闪一下白屏。
- 用 `key={drawingId}` 切换画布时强制重挂，避免上一个画布的 undo 栈残留。

### 5.4 缩略图

```ts
// 0.18.0 的 API：一次调用直接返回已经缩放到 480px 以内的 Blob
const blob = await exportScene({
  data: { elements, appState: persistedAppState, files: api.getFiles() },
  type: "png",
  config: { maxWidthOrHeight: 480, padding: 8, canvasBackgroundColor: "#ffffff" },
});
// Blob → canvas → toDataURL("image/webp", 0.8) → PUT /preview
```

注意 `data` 的字段名是 `appState`（`ExportSceneData`，`excalidraw/packages/excalidraw/scene/export.ts:224`），
里面只认 `.excalidraw` 会持久化的那几个字段（背景色、网格），**不认** `exportBackground` 之类的旧选项——
渲染选项走 `config`。老的 `exportToBlob({ elements, appState, files, exportBackground })` 写法在 0.18 已经不通。

触发时机：保存成功后 `requestIdleCallback(timeout: 2000)`，和保存解耦——缩略图失败不影响数据保存。
列表页 `<img loading="lazy">` 直接用这个 dataURL，不做二次请求。

### 5.5 字体与静态资源

Excalidraw 的字体不在 npm 包的主入口里，必须自己搬：

```jsonc
// vite.config.ts
plugins: [{
  name: "copy-excalidraw-fonts",
  buildStart() { cp("node_modules/@excalidraw/excalidraw/dist/prod/fonts", "public/fonts"); }
}]
```
```html
<!-- index.html -->
<script>
  window.EXCALIDRAW_ASSET_PATH = "/";   // 让组件去 /fonts/xxx.woff2 取字体
</script>
```

不做这一步的表现是：中文/手写字体全部退化成默认字体，而且控制台一堆 404。

---

## 6. Docker 与部署

### 6.1 多阶段构建

```dockerfile
FROM node:22-alpine AS web
WORKDIR /w
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build                       # → /w/dist

FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /b
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/ ./
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN adduser -D -u 10001 app
USER 10001
COPY --from=api /b/target/*.jar /app/app.jar
COPY --from=web /w/dist /app/static
ENV APP_STATIC_LOCATION=file:/app/static/
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
```

### 6.2 compose

```yaml
services:
  app:
    build: .
    ports: ["8080:8080"]
    environment:
      APP_DB_URL: jdbc:mysql://mysql:3306/excalidraw?characterEncoding=utf8&serverTimezone=UTC
      APP_DB_USER: excalidraw
      APP_DB_PASSWORD: ${DB_PASSWORD:?}
      APP_MAX_SCENE_BYTES: "33554432"
    depends_on:
      mysql: { condition: service_healthy }

  mysql:
    image: mysql:8.4
    environment:
      MYSQL_DATABASE: excalidraw
      MYSQL_USER: excalidraw
      MYSQL_PASSWORD: ${DB_PASSWORD:?}
      MYSQL_ROOT_PASSWORD: ${DB_ROOT_PASSWORD:?}
    command: ["--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
              "--max-allowed-packet=64M"]
    volumes: ["mysql-data:/var/lib/mysql"]
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-p$$MYSQL_ROOT_PASSWORD"]
      interval: 5s
      retries: 20

volumes: { mysql-data: {} }
```

### 6.3 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `APP_DB_URL` | `jdbc:mysql://localhost:3306/excalidraw` | 指向外部 MySQL 也行 |
| `APP_DB_USER` / `APP_DB_PASSWORD` | — | 必填 |
| `APP_MAX_SCENE_BYTES` | `33554432` | 单场景 JSON 上限 |
| `APP_MAX_FILE_BYTES` | `8388608` | 单个附件上限（Excalidraw 自身限制 4MiB，留余量） |
| `APP_STORAGE_BLOB` | `jdbc` | `jdbc` / `fs` |
| `APP_STORAGE_BLOB_FS_ROOT` | `/data/blobs` | `fs` 模式的挂载点 |
| `APP_SHARE_BASE_URL` | `http://localhost:8080` | 生成分享链接用 |
| `APP_AUTH_MODE` | `none` | `none` / `password`（M5） |

---

## 7. 里程碑

| 阶段 | 内容 | 验收标准 | 估时 |
|---|---|---|---|
| **M0 骨架** | Spring Boot + Flyway + 健康检查；Vite + `<Excalidraw>` 空白画布；字体本地化 | `mvn spring-boot:run` + `npm run dev` 能画，中文字体正常 | 0.5d |
| **M1 存取闭环** | `POST/GET/PUT` + version 409 + debounce 保存 + 保存状态指示 | 画 → 刷新 → 一致；两个标签页改同一画布，后到者自动合并后落库 | 1.5d |
| **M2 列表页** | 列表/新建/改名/删除 + 缩略图 + 空状态 | 画布能像普通文件一样管理 | 1.5d |
| **M3 附件** | `drawing_file` + 上传/下载 + 4MiB 限制校验 | 贴一张 3MB 图，DB 里场景只有几十 KB | 1d |
| **M4 部署** | 多阶段 Dockerfile + compose + 启动自检日志 | 干净机器上 `docker compose up -d` 后 8080 可用，重启数据不丢 | 0.5d |
| **M5 分享/鉴权** | share_link（token 哈希、可选密码、过期）+ 只读页 + 单密码登录 | 无痕窗口打开分享链接只能看 | 1d |
| **M6 可选** | 历史快照/回滚（当前按需求不做）、.excalidraw 导入导出、协作（Yjs） | — | 按需 |

---

## 8. 已知的坑（按踩中概率排序）

1. **大画布静默 400**：Jackson `StreamConstraintsException`、Tomcat/MySQL 包大小，三处都在 §4.2。
   写完 M1 立刻用 2000 个元素 + 3 张图压测一遍。
2. **缩略图丢图**：只要场景里的 `files` 不是 `data:` 开头，`exportScene` 出来的 PNG 就是空白框。
   MVP 直接返回 dataURL 就绕开了。
3. **滚动位置被持久化**：`appState` 白名单必须严格，否则"打开画布自己乱跳"。
4. **`exportToBlob` 不存在**：照 0.18 之前的教程写会 import 报错；用 `exportScene`。
5. **`reconcileElements` 参数顺序反了**：老代码是 `(remote, local)`，现在是 `(local, remote, appState)`。
6. **升级 Excalidraw 包导致 TS 全红**：`npm i @excalidraw/excalidraw` 会自动跳到 0.19/0.20，
   所有 adapter 都要重看。锁版本 + 升级时只改 `excalidraw-adapter.ts`。
7. **MySQL 行大小/包大小**：`LONGTEXT` 本身能到 4GB，瓶颈是 `max_allowed_packet`（默认 64MB）
   和连接层内存。别用 `SELECT *` 把 elements 拉进列表查询。
8. **非法 UTF-8 / 控制字符**：Excalidraw 的文本里可能带零宽字符，`utf8mb4` 没问题，
   但 MySQL 连接参数必须 `characterEncoding=utf8`，否则 emoji 变 `?`。
9. **keepalive 兜底不可靠**：64KB 上限 + 浏览器会丢弃，别把它当主要保存机制。
10. **许可证**：ExcaliDash 是 LGPL-3.0，Excalidraw 是 MIT。我们可以 MIT 发布，但
    **不要复制 ExcaliDash 的源码**（哪怕几十行），只按文档重写；excalidraw-full 仓库没有 LICENSE 文件，
    同样只参考思路。

---

## 9. 明确不做（MVP 边界）

- 实时协作 / 光标同步 / 评论
- 多用户注册、团队、权限矩阵（先单用户或单密码）
- 素材库（Library）、AI 生成、导出 PDF
- 移动端专门适配（Excalidraw 自身在手机上勉强能用）
- 全文搜索画布内容

---

## 10. 需要你拍板的 5 件事

1. **用户模型**：只给自己用（单密码/免登录），还是一开始就要多用户注册登录？
   —— 影响 `drawing.user_id`、分享权限、审计。
2. **历史版本**：要不要"每次保存留快照 + 可回滚"？要的话 `APP_SNAPSHOT_KEEP` 会给一个上限，
   否则 MySQL 增长很快。
   —— 已定：不要历史版本，只保存最新内容（2026-11-14 用户确认）。
3. **多画布切换体验**：列表页点击是"新标签页打开"还是"同页路由切换"？前者更符合 Excalidraw 使用习惯，
   也避免切换时 undo 栈互相污染。
4. **MongoDB 的时间点**：是"现在就抽象好、一年内不会真接"，还是"这个项目内部就在用 Mongo，
   希望先做 Mongo 版本"？如果是后者，`SceneRepository` 的 Mongo 实现应该提到 M2 而不是以后。
5. **许可证**：ExcalidrawKeep 用 MIT 可以吗？（会决定我能不能引用 ExcaliDash 的代码片段——
   建议不引用，纯按设计重写。）
