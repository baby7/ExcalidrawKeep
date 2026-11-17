# ExcalidrawKeep

把 [Excalidraw](https://github.com/excalidraw/excalidraw) 的画布保存到**你自己的 MySQL** 里。免登录、不需要会员，前端和后端打包在一个 Docker 镜像里。

- 画布列表、重命名、缩略图、新标签页打开
- 1 秒防抖自动保存，顶栏「保存」按钮 / `Ctrl + S` 手动保存
- 多标签页同时编辑：版本号乐观锁 + 元素级合并，不会互相覆盖
- 图片附件按内容 SHA-1 去重，和画布一起存在数据库里
- 浅色 / 深色主题切换（偏好存数据库），列表页和编辑器（顶栏 + 官方画布）都跟着换
- 只保留最新内容，没有历史版本；所有数据都在你自己的库里

## 安装

前提：一个可用的 MySQL 8（字符集 `utf8mb4`）和 Docker。

```bash
docker run -d --name excalidraw-keep \
  --restart unless-stopped \
  -p 8080:8080 \
  -e APP_DB_URL="jdbc:mysql://192.168.1.100:3306/excalidraw_keep?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8" \
  -e APP_DB_USER=root \
  -e APP_DB_PASSWORD=yourpass \
  baby7/excalidraw-keep:latest
```

把 `APP_DB_URL`、`APP_DB_USER`、`APP_DB_PASSWORD` 换成你自己的连接信息，然后打开 **http://服务器IP:8080**。

> 注意：`APP_DB_URL` 里的主机名不能写 `localhost`——容器里的 `localhost` 指容器自己。MySQL 和容器在同一台机器上时，用主机的局域网 IP；Linux 上也可以加 `--add-host=host.docker.internal:host-gateway`，然后把地址换成 `host.docker.internal`。

库不存在时先建一个（表结构由应用自动创建）：

```sql
CREATE DATABASE excalidraw_keep CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
```

## 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_DB_URL` | `jdbc:mysql://localhost:3306/excalidraw_keep?...` | **必改**，你的 MySQL 地址 |
| `APP_DB_USER` / `APP_DB_PASSWORD` | `root` / `root` | 数据库账号密码 |
| `APP_PORT` | `8080` | 宿主机端口 |
| `APP_DDL_AUTO` | `update` | 自动建表/加列 |
| `APP_MAX_SCENE_CHARS` | `33554432` | 单次保存的 JSON 上限（32 MB） |
| `APP_MAX_ATTACHMENT_BYTES` | `8388608` | 单个图片附件上限（8 MB） |
| `JAVA_OPTS` | `-Xms256m -Xmx512m` | JVM 参数 |

固定版本：把 `:latest` 换成 `:0.1.1` 这样的具体版本。

## 升级

```bash
docker pull baby7/excalidraw-keep:latest
docker rm -f excalidraw-keep      # 再执行上面那条 docker run
```

数据在 MySQL 里，重建容器不影响画布。

## 备份

```bash
mysqldump -h 192.168.1.100 -u root -p excalidraw_keep > backup-$(date +%F).sql
```

## 数据表

| 表 | 内容 |
| --- | --- |
| `drawing` | 画布：名称、缩略图、版本号、`elements` / `app_state`（JSON 文本） |
| `drawing_file` | 图片附件，主键 `(drawing_id, file_id)`，同图天然去重 |

## 常见问题

- **连不上数据库**：`Communications link failure` 多半是端口/防火墙，`Access denied` 是账号没有远程授权，`Unknown database` 是库还没建。
- **新建的画布能存，某个老画布一改就报 `Incorrect string value ... for column 'elements'`**：老画布的场景 JSON 里带着历史遗留字符（早先字符集不一致时写进去的）。把它导出成 `.excalidraw` 文件 → 删除这个画布 → 新建画布 → 导入该文件，之后就能正常保存。库/表请保持 `utf8mb4`：

  ```sql
  ALTER TABLE drawing CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  ALTER TABLE drawing_file CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  ALTER TABLE app_setting CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
  ```
- **保存大画布报 413 / 500**：同时调大 `APP_MAX_SCENE_CHARS` 和 MySQL 的 `max_allowed_packet`。
- **界面还是旧的 / 字体 404**：静态资源是 `no-cache`，强制刷新（`Ctrl + Shift + R`）即可。

排错入口：`docker logs -f excalidraw-keep`、`curl http://localhost:8080/actuator/health`。

## 开发

前端 `excalidraw-keep-web`（Vite + React 18 + `@excalidraw/excalidraw` 0.18.0），后端 `excalidraw-keep-server`（Spring Boot 2.6.13 / Java 8）。打包：`bash scripts/build-web.sh`。

## License

MIT
