# StoreWeave

StoreWeave 是一个面向 Java 17+ 的可插拔存储访问框架。核心 API 不依赖 Spring，也不暴露任何云厂商 SDK 类型；应用只引入实际使用的 Provider。

> Local 与 S3 Provider 已可用，其他 Provider 将按契约测试逐个迁移。

## 模块

| 模块 | 职责 |
|---|---|
| `storeweave-core` | 公共 API、模型、能力接口、Provider SPI、注册中心 |
| `storeweave-testkit` | 所有 Provider 共用的行为契约测试 |
| `storeweave-provider-local` | 本地文件系统 Provider，也是参考实现 |
| `storeweave-provider-s3` | 基于 AWS SDK 2.x 的 S3 与 S3 兼容存储 Provider |
| `storeweave-spring-boot-autoconfigure` | 配置绑定与自动装配 |
| `storeweave-spring-boot-starter` | Spring Boot 使用入口，不捆绑 Provider |
| `storeweave-example-spring-boot` | 可运行示例 |

计划中的 Provider：MinIO、Aliyun OSS、Tencent COS、Huawei OBS、FTP、SFTP。

## Provider 能力

| Provider | 对象操作 | 桶管理 | 预签 URL | 分片上传 |
|---|---:|---:|---:|---:|
| Local | ✓ | ✓ | — | — |
| S3 | ✓ | ✓ | ✓ | ✓ |

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

Starter 不会自动带入 Local、S3 或其他 Provider。应用必须显式选择 Provider 模块。

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

S3 配置、默认凭证链和迁移注意事项见 [S3 Provider 文档](docs/providers/s3.md)。示例工程默认使用 Local，启用 `s3` Profile 即可切换：

```powershell
$env:STOREWEAVE_S3_ENDPOINT = "http://127.0.0.1:9000"
$env:AWS_ACCESS_KEY_ID = "your-access-key"
$env:AWS_SECRET_ACCESS_KEY = "your-secret-key"
.\mvnw.cmd -pl storeweave-examples/storeweave-example-spring-boot -am spring-boot:run "-Dspring-boot.run.profiles=s3"
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
