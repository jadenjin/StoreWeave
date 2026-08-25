# Aliyun OSS Provider

`storeweave-provider-aliyun-oss` 基于 Aliyun OSS Java SDK 3.18.5，独立于 S3 和其他 Provider。SDK 类型只存在于本模块内部。

## 配置

```yaml
storeweave:
  primary: aliyun
  stores:
    aliyun:
      type: aliyun-oss
      endpoint: https://oss-cn-hangzhou.aliyuncs.com
      region: cn-hangzhou
      access-key: ${ALIBABA_CLOUD_ACCESS_KEY_ID}
      secret-key: ${ALIBABA_CLOUD_ACCESS_KEY_SECRET}
      session-token: ${ALIBABA_CLOUD_SECURITY_TOKEN:}
      options:
        connect-timeout-ms: 50000
        socket-timeout-ms: 50000
        max-error-retry: 3
        sld-enabled: false
```

| 配置 | 必填 | 默认值 | 说明 |
|---|---:|---|---|
| `type` | 是 | — | 固定为 `aliyun-oss` |
| `endpoint` | 是 | — | OSS 的绝对 HTTP(S) endpoint |
| `region` | 否 | — | 保留给应用配置和后续 V4 签名迁移；当前 SDK 从 endpoint 解析区域 |
| `access-key` / `secret-key` | 是 | — | 静态凭证，必须同时提供 |
| `session-token` | 否 | — | STS 临时凭证使用 |
| `options.connect-timeout-ms` | 否 | `50000` | 连接超时 |
| `options.socket-timeout-ms` | 否 | `50000` | Socket 读取超时 |
| `options.max-error-retry` | 否 | `3` | SDK 最大错误重试次数，允许 `0` |
| `options.sld-enabled` | 否 | `false` | 自定义二级域名寻址；普通阿里云 endpoint 保持关闭 |

## 能力

| 能力 | 支持 | 说明 |
|---|---:|---|
| 通用对象操作 | ✓ | 上传、下载、元数据、存在性、删除、marker 分页 |
| `BucketOperations` | ✓ | 创建、检查、列表和删除桶 |
| `PresignOperations` | ✓ | 预签 GET 与 PUT，可携带请求头 |
| `MultipartOperations` | ✓ | 发起、上传 Part、列出 Part、完成和终止 |
| `LifecycleOperations` | ✓ | 按规则 ID 管理前缀、到期天数和启用状态 |
| 通知、配额、服务管理 | — | 旧实现是空操作或不完整策略模拟，新版本不声明 |

SDK 服务端异常会映射为统一的不存在、未授权、冲突、请求无效或 Provider 错误。超时、限流、服务不可用、内部错误和客户端网络异常会标记为可重试；Provider 不会自行重放写操作。

模块契约测试使用内存 OSS 边界替身，不需要云账号。部署前仍应使用环境变量凭证执行真实 OSS 冒烟测试。
