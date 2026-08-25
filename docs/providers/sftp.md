# SFTP Provider

`storeweave-provider-sftp` 基于 `com.github.mwiede:jsch` 2.28.6。它把配置的远端根目录视为 StoreWeave 命名空间：桶对应根目录下的一级目录，对象 key 对应桶目录内的相对文件路径。

## 配置

```yaml
storeweave:
  primary: sftp
  stores:
    sftp:
      type: sftp
      endpoint: sftp://sftp.example.com:22
      access-key: ${STOREWEAVE_SFTP_USERNAME}
      secret-key: ${STOREWEAVE_SFTP_PASSWORD}
      options:
        root-directory: /storeweave
        connect-timeout-ms: 5000
        socket-timeout-ms: 30000
        strict-host-key-checking: true
        known-hosts: C:/Users/example/.ssh/known_hosts
```

| 配置 | 必填 | 默认值 | 说明 |
|---|---:|---|---|
| `type` | 是 | — | 固定为 `sftp` |
| `endpoint` | 是 | — | `sftp://host[:port]`，不得包含凭证和路径 |
| `access-key` / `secret-key` | 是 | — | 分别作为 SSH 用户名和密码 |
| `options.root-directory` | 否 | `/` | StoreWeave 专用远端根目录 |
| `options.connect-timeout-ms` | 否 | `5000` | 建立 Session 和 Channel 的超时毫秒数 |
| `options.socket-timeout-ms` | 否 | `30000` | Session Socket 超时毫秒数 |
| `options.strict-host-key-checking` | 否 | `true` | 是否拒绝未知或已变化的服务端主机密钥 |
| `options.known-hosts` | 严格校验时是 | — | OpenSSH `known_hosts` 文件路径 |

## 语义与能力

- 支持通用对象操作和 `BucketOperations`，不声明预签、分片、生命周期等 SFTP 无法可靠表达的能力。
- 桶名必须是安全的单级目录名；对象 key 必须是相对路径，禁止空段、`.`、`..`、反斜杠和控制字符。
- 上传先写同目录临时文件，再通过重命名替换目标；覆盖时保留临时备份直到替换成功。
- 列表会递归扫描桶目录，按对象 key 排序后应用前缀和 continuation token。该 token 是对象 key，仅保证同一次稳定目录视图下的分页语义。
- 每次短操作建立并关闭一个 SSH Session 与 SFTP Channel；下载流独占二者，并在流关闭时释放。调用方必须及时关闭下载流。
- 删除对象后会清理其产生的空父目录，但不会删除桶目录。删除非空桶会返回冲突错误。

主机密钥校验默认开启，且开启时必须显式配置 `known-hosts`。仅可在受控的本地测试环境中关闭校验；生产环境关闭会失去对中间人攻击的防护。
