# StoreWeave 架构说明

## 目标

StoreWeave 为对象存储、本地文件系统和文件传输协议提供一致的调用入口，同时保留不同 Provider 的能力差异。框架不试图把所有厂商特性压缩成一个巨大接口。

## 依赖边界

```text
example -> starter -> autoconfigure -> core
example -> provider -> core
provider tests -> testkit -> core
```

禁止的依赖方向：

- `core -> Spring`
- `core -> provider SDK`
- `provider -> Spring`
- `provider A -> provider B`
- `starter -> 任意具体 provider`

## API 分层

`StorageClient` 只包含所有存储实现都能遵循的对象操作：上传、下载、元数据、存在性、删除和分页列表。

| 能力 | 接口 |
|---|---|
| 桶管理 | `BucketOperations` |
| 预签 URL | `PresignOperations` |
| 分片上传 | `MultipartOperations` |
| 生命周期 | `LifecycleOperations` |
| 事件通知 | `NotificationOperations` |
| 桶配额 | `QuotaOperations` |
| 服务管理 | `ServerAdminOperations` |

调用方必须显式查询能力：

```java
PresignOperations presign = client.capability(PresignOperations.class)
        .orElseThrow(() -> new IllegalStateException("Provider does not support presigning"));
```

## Provider 发现和生命周期

Provider 实现 `StorageProvider`，并在 `META-INF/services/com.jdc.storeweave.core.spi.StorageProvider` 注册。`StorageRegistry` 使用 `ServiceLoader` 发现 Provider，根据配置创建命名客户端，并负责替换、移除和关闭客户端。

Spring Boot 自动配置只是对同一套 Core API 的适配，不是另一个运行模型。

## 数据与错误语义

- 对象使用 `StorageObject(bucket, key)` 标识。
- 数据源使用 `ObjectContent`，不在 Core 中使用 `MultipartFile`。
- 时间使用 `Instant` 和 `Duration`。
- URL 使用 `URI`。
- Provider 异常统一转换为 `StorageException`，保留错误类别、Provider 类型和是否可重试。
- continuation token 只保证在同一次分页扫描中有效。

## Provider 迁移顺序

1. Local（已完成）：确定统一语义和契约测试。
2. S3（已完成）：覆盖主流对象操作、预签和分片上传。
3. MinIO：对象操作以及 MinIO 特有的管理能力。
4. Aliyun OSS、Tencent COS、Huawei OBS。
5. FTP、SFTP：明确其目录语义与对象存储差异。

每个 Provider 只有在基础契约通过且没有泄漏 SDK 类型后，才加入根聚合构建。

当前能力矩阵和 S3 配置见 [S3 Provider 文档](providers/s3.md)。
