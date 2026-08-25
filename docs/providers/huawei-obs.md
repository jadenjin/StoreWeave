# Huawei OBS Provider

`storeweave-provider-huawei-obs` 基于华为云 OBS Java SDK 3.26.6，独立于其他 Provider。SDK 类型只存在于本模块内部。

## 配置

```yaml
storeweave:
  primary: huawei
  stores:
    huawei:
      type: huawei-obs
      endpoint: https://obs.cn-south-1.myhuaweicloud.com
      region: cn-south-1
      access-key: ${HUAWEI_CLOUD_ACCESS_KEY}
      secret-key: ${HUAWEI_CLOUD_SECRET_KEY}
      session-token: ${HUAWEI_CLOUD_SECURITY_TOKEN:}
      options:
        connect-timeout-ms: 5000
        socket-timeout-ms: 30000
        max-error-retry: 3
        path-style-access: false
```

| 配置 | 必填 | 默认值 | 说明 |
|---|---:|---|---|
| `type` | 是 | — | 固定为 `huawei-obs` |
| `endpoint` | 是 | — | OBS 的绝对 HTTP(S) endpoint |
| `region` | 否 | — | 创建桶时使用的位置；访问已有桶可省略 |
| `access-key` / `secret-key` | 是 | — | 静态凭证，必须同时提供 |
| `session-token` | 否 | — | 临时安全凭证使用 |
| `options.connect-timeout-ms` | 否 | `5000` | 连接超时 |
| `options.socket-timeout-ms` | 否 | `30000` | Socket 读取超时 |
| `options.max-error-retry` | 否 | `3` | SDK 最大错误重试次数，允许 `0` |
| `options.path-style-access` | 否 | `false` | 是否使用路径式桶寻址 |

## 能力

| 能力 | 支持 | 说明 |
|---|---:|---|
| 通用对象操作 | ✓ | 上传、下载、元数据、存在性、删除、marker 分页 |
| `BucketOperations` | ✓ | 创建、检查、列表和删除桶 |
| `PresignOperations` | ✓ | 临时签名 GET 与 PUT，可携带请求头 |
| `MultipartOperations` | ✓ | 发起、上传 Part、列出 Part、完成和终止 |
| `LifecycleOperations` | ✓ | 按规则 ID 管理前缀、到期天数和启用状态 |
| 通知、配额、服务管理 | — | 旧实现没有可移植的真实实现，新版本不声明 |

SDK 异常会映射为统一的不存在、未授权、冲突、请求无效或 Provider 错误。HTTP 408、429、5xx、限流、超时和 I/O 异常会标记为可重试；Provider 不会自行重放写操作。

模块契约测试使用内存 OBS 边界替身，不需要云账号。部署前仍应使用环境变量凭证执行真实 OBS 冒烟测试。
