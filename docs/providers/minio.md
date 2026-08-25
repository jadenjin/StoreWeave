# MinIO Provider

`storeweave-provider-minio` 基于 MinIO Java SDK 8.6.0，独立于 S3 Provider。普通对象操作遵循 StoreWeave 通用契约，MinIO 管理功能通过可选能力接口提供，不向 Core 泄漏 SDK 类型。

## 引入与配置

应用在 Starter 之外显式引入 `storeweave-provider-minio`。项目当前只从源码构建，不配置远程 Maven 发布。

```yaml
storeweave:
  primary: minio
  stores:
    minio:
      type: minio
      endpoint: http://127.0.0.1:9000
      region: us-east-1
      access-key: ${MINIO_ROOT_USER}
      secret-key: ${MINIO_ROOT_PASSWORD}
      options:
        connect-timeout-ms: 5000
        read-timeout-ms: 300000
        write-timeout-ms: 300000
```

| 配置 | 必填 | 默认值 | 说明 |
|---|---:|---|---|
| `type` | 是 | — | 固定为 `minio` |
| `endpoint` | 是 | — | MinIO 服务的绝对 HTTP(S) 地址 |
| `region` | 否 | SDK 默认值 | 用于请求签名和建桶 |
| `access-key` / `secret-key` | 是 | — | MinIO 静态凭证 |
| `session-token` | 否 | — | 临时静态凭证使用 |
| `options.connect-timeout-ms` | 否 | `5000` | 连接超时，单位毫秒 |
| `options.read-timeout-ms` | 否 | `300000` | 读取超时，单位毫秒 |
| `options.write-timeout-ms` | 否 | `300000` | 写入超时，单位毫秒 |

不要把密钥写入源码或提交到 Git。客户端关闭时会释放同步、异步和 HTTP 连接资源。

## 能力

| 能力 | 支持 | 说明 |
|---|---:|---|
| 通用对象操作 | ✓ | 上传、下载、元数据、存在性、删除、token 分页 |
| `BucketOperations` | ✓ | 创建、检查、列表和删除桶 |
| `PresignOperations` | ✓ | 预签 GET 与 PUT，最长 7 天 |
| `MultipartOperations` | ✓ | 发起、上传 Part、列出 Part、完成和终止 |
| `LifecycleOperations` | ✓ | 按规则 ID 管理前缀与到期天数 |
| `NotificationOperations` | ✓ | 配置 MinIO Queue Target 的创建/删除事件 |
| `QuotaOperations` | ✓ | 设置、查询和清除桶硬配额 |
| `ServerAdminOperations` | ✓ | 查询版本、部署模式、磁盘和用量摘要 |

通知的 `destination` 必须是 MinIO 已配置的通知目标 ARN，例如 `arn:minio:sqs:us-east-1:primary:webhook`，不是任意 Webhook URL。配额按字节暴露，但 MinIO Admin API 的最小精度是 1 KiB，因此设置值必须是 1024 的正整数倍。

## 错误和重试语义

SDK 和 Admin 异常统一映射为 `StorageException`。HTTP 408、429、5xx、网络 I/O 和无效服务响应会标记为可重试；认证失败、资源不存在、冲突和请求无效默认不可重试。Provider 不会隐式重放写操作。

## 测试范围

模块使用进程内 S3 兼容 HTTP 服务验证对象契约、分页、桶管理、预签、分片、生命周期、通知和错误映射；Admin 能力通过边界替身验证，无需 Docker 或外部账号。连接真实 MinIO 时仍建议补充部署环境的冒烟测试，尤其是通知目标和 Admin 权限。
