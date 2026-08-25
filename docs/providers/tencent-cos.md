# Tencent COS Provider

`storeweave-provider-tencent-cos` 基于腾讯云 COS Java SDK 5.6.275，独立于 S3 和其他 Provider。SDK 类型只存在于本模块内部，且已排除 SDK 间接引入的旧版 `ini4j:0.5.4`。

## 配置

```yaml
storeweave:
  primary: tencent
  stores:
    tencent:
      type: tencent-cos
      region: ap-guangzhou
      access-key: ${TENCENT_CLOUD_SECRET_ID}
      secret-key: ${TENCENT_CLOUD_SECRET_KEY}
      session-token: ${TENCENT_CLOUD_SESSION_TOKEN:}
      options:
        protocol: https
        connect-timeout-ms: 5000
        socket-timeout-ms: 30000
        max-error-retry: 3
```

| 配置 | 必填 | 默认值 | 说明 |
|---|---:|---|---|
| `type` | 是 | — | 固定为 `tencent-cos` |
| `region` | 是 | — | COS 地域，例如 `ap-guangzhou` |
| `access-key` / `secret-key` | 是 | — | SecretId 与 SecretKey，必须同时提供 |
| `session-token` | 否 | — | 临时密钥的 Token |
| `options.protocol` | 否 | `https` | 允许 `http` 或 `https` |
| `options.connect-timeout-ms` | 否 | `5000` | 连接超时 |
| `options.socket-timeout-ms` | 否 | `30000` | Socket 读取超时 |
| `options.max-error-retry` | 否 | `3` | SDK 最大错误重试次数，允许 `0` |

## 能力

| 能力 | 支持 | 说明 |
|---|---:|---|
| 通用对象操作 | ✓ | 上传、下载、元数据、存在性、删除、marker 分页 |
| `BucketOperations` | ✓ | 创建、检查、列表和删除桶 |
| `PresignOperations` | ✓ | 预签 GET 与 PUT，可携带签名请求头 |
| `MultipartOperations` | ✓ | 发起、上传 Part、列出 Part、完成和终止 |
| `LifecycleOperations` | ✓ | 按规则 ID 管理前缀、到期天数和启用状态 |
| 通知、配额、服务管理 | — | 旧实现未真正提供通用能力，新版本不声明 |

SDK 服务端异常会映射为统一的不存在、未授权、冲突、请求无效或 Provider 错误。超时、限流、服务端错误和可重试客户端异常会标记为可重试；Provider 不会自行重放写操作。

模块契约测试使用内存 COS 边界替身，不需要云账号。部署前仍应使用环境变量凭证执行真实 COS 冒烟测试。
