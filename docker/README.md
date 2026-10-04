# Docker 分区

本分区包含内容服务、内置 Web 客户端与 Docker 部署配置。

- `server/`：Python 服务端、Web 客户端、测试及工具，详见[服务端说明](server/README.md)。
- `docker-compose.yml`：本地部署入口。
- `library/`：本地曲库，只读挂载到容器，不提交到仓库。
- `server/data/`：Compose 服务的持久化数据，不提交到仓库。
- `data/`：本地开发的默认数据目录，不提交到仓库。

从仓库根目录运行：

```shell
docker compose -f docker/docker-compose.yml up --build
```

将歌曲放入 `docker/library/`，启动后访问 `http://localhost:8080/`。
共享契约和测试资料位于仓库根目录的 `shared/`。
