# FTP Provider

`storeweave-provider-ftp` 基于 Apache Commons Net 3.13.0。它把配置的远端根目录视为 StoreWeave 命名空间：桶对应根目录下的一级目录，对象 key 对应桶目录内的相对文件路径。

## 配置

```yaml
storeweave:
  primary: ftp
  stores:
    ftp:
      type: ftp
      endpoint: ftp://ftp.example.com:21
      access-key: ${STOREWEAVE_FTP_USERNAME}
      secret-key: ${STOREWEAVE_FTP_PASSWORD}
      options:
        root-directory: /storeweave
        connect-timeout-ms: 5000
        socket-timeout-ms: 30000
        passive-mode: true
```

| 配置 | 必填 | 默认值 | 说明 |
|---|---:|---|---|
| `type` | 是 | — | 固定为 `ftp` |
| `endpoint` | 是 | — | `ftp://host[:port]`，不得包含凭证和路径 |
| `access-key` / `secret-key` | 是 | — | 分别作为 FTP 用户名和密码 |
| `options.root-directory` | 否 | `/` | StoreWeave 专用远端根目录 |
| `options.connect-timeout-ms` | 否 | `5000` | 建立连接的超时毫秒数 |
| `options.socket-timeout-ms` | 否 | `30000` | 控制与数据连接的读取超时毫秒数 |
| `options.passive-mode` | 否 | `true` | 是否使用被动模式 |

## 语义与能力

- 支持通用对象操作和 `BucketOperations`，不声明预签、分片、生命周期等 FTP 无法可靠表达的能力。
- 桶名必须是安全的单级目录名；对象 key 必须是相对路径，禁止空段、`.`、`..`、反斜杠和控制字符。
- 上传先写同目录临时文件，再通过重命名替换目标；覆盖时保留临时备份直到替换成功。
- 列表会递归扫描桶目录，按对象 key 排序后应用前缀和 continuation token。该 token 是对象 key，仅保证同一次稳定目录视图下的分页语义。
- 每次短操作建立并关闭一个 FTP 连接；下载流独占连接，并在流关闭时完成 FTP pending command 后断开。调用方必须及时关闭下载流。
- 删除对象后会清理其产生的空父目录，但不会删除桶目录。删除非空桶会返回冲突错误。

FTP 不提供传输加密。跨不可信网络部署时应优先使用 SFTP；真实部署还应针对目标服务端验证重命名语义、字符集和防火墙下的数据连接模式。
