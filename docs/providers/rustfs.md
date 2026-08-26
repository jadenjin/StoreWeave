# RustFS Provider

RustFS 暴露兼容 Amazon S3 的 REST API，并官方推荐使用 AWS S3 SDK。StoreWeave 因此在 `storeweave-provider-s3` 模块中注册独立的 `rustfs` Provider 类型，复用经过契约测试的 AWS SDK 2.x 客户端实现，同时提供符合 RustFS 的配置默认值。

## 配置

```yaml
storeweave:
  primary: rustfs
  stores:
    rustfs:
      type: rustfs
      endpoint: https://rustfs.example.com:9000
      region: us-east-1
      access-key: ${STOREWEAVE_RUSTFS_ACCESS_KEY}
      secret-key: ${STOREWEAVE_RUSTFS_SECRET_KEY}
      session-token: ${STOREWEAVE_RUSTFS_SESSION_TOKEN:}
      options:
        path-style-access: true
```

| 配置 | 必填 | 默认值 | 说明 |
|---|---:|---|---|
| `type` | 是 | — | 固定为 `rustfs` |
| `endpoint` | 是 | — | RustFS S3 API 的绝对 HTTP(S) 地址，不得包含凭证、路径、查询或片段 |
| `region` | 否 | `us-east-1` | SigV4 签名 Region，应与 `RUSTFS_REGION` 一致 |
| `access-key` / `secret-key` | 是 | — | RustFS IAM 用户或服务账号凭证，必须同时提供 |
| `session-token` | 否 | — | STS 临时凭证使用 |
| `options.path-style-access` | 否 | `true` | 默认使用 RustFS 的路径式桶寻址 |

RustFS 默认在 `9000` 端口提供 S3 API，默认签名 Region 为 `us-east-1`。生产环境应使用 HTTPS、专用 IAM 用户或服务账号，并按最小权限授权，不要使用或提交根凭证。

若服务端已配置 `RUSTFS_SERVER_DOMAINS`、通配 DNS 与匹配的 TLS 证书，可以把 `path-style-access` 设为 `false` 使用虚拟主机式寻址。否则应保留默认值 `true`。

## 能力

| 能力 | 支持 | 说明 |
|---|---:|---|
| 通用对象操作 | ✓ | 上传、下载、用户元数据、存在性、删除、ListObjectsV2 分页 |
| `BucketOperations` | ✓ | 创建、检查、列表和删除桶 |
| `PresignOperations` | ✓ | SigV4 预签 GET 与 PUT |
| `MultipartOperations` | ✓ | 发起、上传 Part、列出 Part、完成和终止 |
| 生命周期、通知、配额、服务管理 | — | 当前 StoreWeave RustFS 类型不声明这些可选能力 |

RustFS 的 S3 兼容范围不是所有 AWS 或 MinIO 专有行为。不要依赖 ACL 授权、Bucket Ownership Controls、Bucket Access Logging 或 MinIO Admin API；高级能力应先针对目标 RustFS 版本验证。StoreWeave 当前只声明自身已经实现并纳入契约测试的能力。

## 真实服务集成测试

常规构建使用进程内 S3 兼容服务，不需要外部账号。要对真实 RustFS 实例执行冒烟测试，设置以下环境变量后运行 `verify`：

```powershell
$env:STOREWEAVE_RUSTFS_INTEGRATION_ENDPOINT = "http://127.0.0.1:9000"
$env:STOREWEAVE_RUSTFS_ACCESS_KEY = "your-access-key"
$env:STOREWEAVE_RUSTFS_SECRET_KEY = "your-secret-key"
$env:STOREWEAVE_RUSTFS_REGION = "us-east-1"
.\mvnw.cmd -pl storeweave-providers/storeweave-provider-s3 -am verify
```

未配置 `STOREWEAVE_RUSTFS_INTEGRATION_ENDPOINT` 时，真实服务测试会自动跳过。测试使用随机桶并清理对象，不会记录凭证或预签 URL。

兼容范围与部署约定以 [RustFS S3 协议文档](https://docs.rustfs.com/en/administration/protocols/s3) 和 [S3 兼容矩阵](https://docs.rustfs.com/en/reference/s3-compatibility) 为准。
