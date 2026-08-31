# StoreWeave

StoreWeave 是一个面向 Java 17+ 的可插拔存储访问框架。核心 API 不依赖 Spring，也不暴露任何云厂商 SDK 类型；应用只引入实际使用的 Provider。

> Local、S3、RustFS、MinIO、Aliyun OSS、Tencent COS、Huawei OBS、FTP 与 SFTP Provider 已可用。

## 模块

| 模块 | 职责 |
|---|---|
| `storeweave-core` | 公共 API、模型、能力接口、Provider SPI、注册中心 |
| `storeweave-testkit` | 所有 Provider 共用的行为契约测试 |
| `storeweave-provider-local` | 本地文件系统 Provider，也是参考实现 |
| `storeweave-provider-s3` | 基于 AWS SDK 2.x 的 S3 协议 Provider，包含 AWS S3 与 RustFS 类型 |
| `storeweave-provider-minio` | MinIO 对象访问与管理能力 Provider |
| `storeweave-provider-aliyun-oss` | 阿里云 OSS Provider |
| `storeweave-provider-tencent-cos` | 腾讯云 COS Provider |
| `storeweave-provider-huawei-obs` | 华为云 OBS Provider |
| `storeweave-provider-ftp` | 基于 Commons Net 的 FTP Provider |
| `storeweave-provider-sftp` | 基于 JSch 的 SFTP Provider |
| `storeweave-providers-all` | 一次引入全部 Provider 的便捷聚合依赖 |
| `storeweave-spring-boot-autoconfigure` | 配置绑定与自动装配 |
| `storeweave-spring-boot-starter` | Spring Boot 使用入口，不捆绑 Provider |
| `storeweave-example-spring-boot` | 可运行示例 |

## Provider 能力

| Provider | 对象操作 | 桶管理 | 预签 URL | 分片上传 | 生命周期 | 通知 | 配额 | 服务管理 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Local | ✓ | ✓ | — | — | — | — | — | — |
| S3 | ✓ | ✓ | ✓ | ✓ | — | — | — | — |
| RustFS | ✓ | ✓ | ✓ | ✓ | — | — | — | — |
| MinIO | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Aliyun OSS | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — |
| Tencent COS | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — |
| Huawei OBS | ✓ | ✓ | ✓ | ✓ | ✓ | — | — | — |
| FTP | ✓ | ✓ | — | — | — | — | — | — |
| SFTP | ✓ | ✓ | — | — | — | — | — | — |

## 构建

项目不配置远程 Maven 发布。克隆源码后直接使用 Wrapper 构建：

```powershell
.\mvnw.cmd clean verify
```

运行示例：

```powershell
.\mvnw.cmd -pl storeweave-examples/storeweave-example-spring-boot -am spring-boot:run
```

## Spring Boot 配置

Starter 不会自动带入 Local、S3 或其他 Provider。应用可以按需引入单个 Provider；需要全部实现时，也可以只引入聚合模块：

```xml
<dependency>
    <groupId>com.jdc</groupId>
    <artifactId>storeweave-providers-all</artifactId>
</dependency>
```

该依赖会传递引入 Local、S3/RustFS、MinIO、Aliyun OSS、Tencent COS、Huawei OBS、FTP 和 SFTP。只使用少数存储时，仍建议按需引入对应 Provider，以减小依赖体积。

```yaml
storeweave:
  primary: local
  stores:
    local:
      type: local
      endpoint: file:./target/storeweave-data
```

```java
@Service
class DocumentService {
    private final StorageClient storage;

    DocumentService(StorageClient storage) {
        this.storage = storage;
    }
}
```

也可以注入 `StorageRegistry`，通过配置名称选择多个存储实例：

```java
StorageClient archive = registry.required("archive");
```

Provider 配置见 [Local](docs/providers/local.md)、[S3](docs/providers/s3.md)、[RustFS](docs/providers/rustfs.md)、[MinIO](docs/providers/minio.md)、[Aliyun OSS](docs/providers/aliyun-oss.md)、[Tencent COS](docs/providers/tencent-cos.md)、[Huawei OBS](docs/providers/huawei-obs.md)、[FTP](docs/providers/ftp.md) 和 [SFTP](docs/providers/sftp.md) 文档。示例工程默认使用 Local，可通过对应 Profile 切换。

```powershell
$env:STOREWEAVE_S3_ENDPOINT = "http://127.0.0.1:9000"
$env:AWS_ACCESS_KEY_ID = "your-access-key"
$env:AWS_SECRET_ACCESS_KEY = "your-secret-key"
.\mvnw.cmd -pl storeweave-examples/storeweave-example-spring-boot -am spring-boot:run "-Dspring-boot.run.profiles=s3"
```

```powershell
$env:STOREWEAVE_MINIO_ENDPOINT = "http://127.0.0.1:9000"
$env:MINIO_ROOT_USER = "your-access-key"
$env:MINIO_ROOT_PASSWORD = "your-secret-key"
.\mvnw.cmd -pl storeweave-examples/storeweave-example-spring-boot -am spring-boot:run "-Dspring-boot.run.profiles=minio"
```

RustFS 使用独立类型并默认启用 path-style：

```powershell
$env:STOREWEAVE_RUSTFS_ENDPOINT = "http://127.0.0.1:9000"
$env:STOREWEAVE_RUSTFS_ACCESS_KEY = "your-access-key"
$env:STOREWEAVE_RUSTFS_SECRET_KEY = "your-secret-key"
.\mvnw.cmd -pl storeweave-examples/storeweave-example-spring-boot -am spring-boot:run "-Dspring-boot.run.profiles=rustfs"
```

## 设计约束

- Core 只能依赖 JDK。
- Provider 只能依赖 Core 和自身 SDK，Provider 之间不能互相依赖。
- Spring 类型只能存在于 Spring 模块。
- 可选能力通过 `capability(...)` 查询，不允许用空实现伪装支持。
- 所有 Provider 必须通过 `storeweave-testkit` 的基础对象生命周期契约。

完整设计见 [架构说明](docs/architecture.md)。

## License

Apache License 2.0。
