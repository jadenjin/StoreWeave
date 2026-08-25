# Local Provider

`storeweave-provider-local` 使用 JDK `java.nio.file` 访问本地文件系统，不依赖第三方 SDK。它也是 StoreWeave 基础对象语义和公共契约的参考实现。

## 配置

```yaml
storeweave:
  primary: local
  stores:
    local:
      type: local
      endpoint: file:./target/storeweave-data
```

也可以使用绝对文件 URI。Windows 示例：

```yaml
endpoint: file:///D:/storeweave-data
```

| 配置 | 必填 | 默认值 | 说明 |
|---|---:|---|---|
| `type` | 是 | — | 固定为 `local` |
| `endpoint` | 是 | — | 本地存储根目录，推荐使用 `file:` URI |
| `region` | 否 | — | Local Provider 不使用 |
| 凭证 | 否 | — | Local Provider 不使用 |
| `options` | 否 | — | 当前没有 Provider 专用选项 |

相对路径会根据应用进程的当前工作目录解析。客户端创建时会自动创建不存在的存储根目录；应用进程必须对该目录拥有所需的读写权限。

## 目录映射

```text
<endpoint>/
  <bucket>/
    <object-key>
```

桶对应根目录下的一级目录，对象 key 对应桶目录内的相对文件路径。例如 `StorageObject("documents", "reports/2026/result.txt")` 会映射为：

```text
<endpoint>/documents/reports/2026/result.txt
```

桶名不能是 `.`、`..`，也不能包含路径分隔符。对象 key 由 Core 拒绝绝对路径、空路径段及 `.`、`..` 路径段，最终路径还会经过规范化和根目录边界检查。

## 能力与行为

| 能力 | 支持 | 说明 |
|---|---:|---|
| 通用对象操作 | ✓ | 上传、下载、元数据、存在性、删除、前缀分页 |
| `BucketOperations` | ✓ | 创建、检查、列表和删除目录桶 |
| 预签、分片、生命周期、通知、配额、服务管理 | — | 本地文件系统不声明这些可选能力 |

- 上传先写入根目录内的临时文件，再尝试原子移动并覆盖目标；文件系统不支持原子移动时退化为普通替换移动。
- `PutObjectResult.eTag` 和对象元数据中的 ETag 是文件内容的 SHA-256 十六进制摘要。
- `contentType` 由操作系统文件类型探测得到，可能为空；当前不持久化用户自定义元数据。
- continuation token 是最后一个对象 key，仅保证同一次稳定目录视图下的分页语义。
- 删除对象后会清理对象路径产生的空父目录，但保留桶目录；删除非空桶会返回冲突错误。
- `get(...)` 返回打开的文件流，调用方必须及时关闭。

Local Provider 适合开发、测试、单机应用和由外部系统保证共享一致性的挂载目录。它不提供跨进程锁、分布式一致性或多节点协调。不要允许不受信任的用户在存储根目录内创建符号链接；需要隔离时应配合专用目录和操作系统权限。
