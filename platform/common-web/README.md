# common-web

English | [简体中文](#简体中文)

## English

Shared HTTP response and exception contracts used by the application and business modules.

### Current Capabilities

- `ApiResponse` success/failure envelope.
- Stable `ErrorCode` values.
- `BusinessException`.
- Validation and pagination response models.
- `GlobalExceptionHandler` for safe HTTP error mapping.

### Boundary

The module currently also provides error types used by non-web platform modules. This coupling is acceptable at the current size. Do not create a separate `common-kernel` until non-web reuse grows enough to justify it.

Its auto-configuration/registration must be made explicit so reuse does not depend on the host application's package scan.

### Test

```bash
../../mvnw -f ../../pom.xml -pl platform/common-web -am test
```

## 简体中文

该模块提供统一 API 响应、错误码、业务异常、校验错误、分页结果和全局异常处理。

目前非 Web 平台模块也使用其中的错误模型，规模尚小，不急于拆 `common-kernel`。后续只需修复显式自动装配，避免依赖宿主应用包扫描。
