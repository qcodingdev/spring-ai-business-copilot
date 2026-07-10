# business-copilot-app

English | [简体中文](#简体中文)

## English

The executable Spring Boot application that assembles all implemented Copilot and platform modules.

### Responsibilities

- Own the application entry point and runtime configuration.
- Assemble Data, Knowledge, Support, and Report Copilot.
- Host Thymeleaf templates, JavaScript, CSS, and sample resources.
- Own Flyway migrations and PostgreSQL runtime dependencies.
- Expose Actuator health information.

### Boundaries

- Business rules belong in `modules/*`, not in the app module.
- Shared AI and safety capabilities belong in `platform/*` only after real reuse exists.
- Flyway is the only schema-management mechanism.

### Run

```bash
../../mvnw -f ../../pom.xml -q -DskipTests install
../../mvnw -f ../../pom.xml -pl app/business-copilot-app spring-boot:run
```

Run these commands from this directory. Chat and embedding are disabled by default; enable them through environment variables described in the root README.

### Framework Plan

The app now receives `mybatis-plus-spring-boot4-starter` through Report Copilot. Its initial source-preview feature does not persist data yet; later stable report CRUD may use MyBatis-Plus. Dynamic SQL and pgvector repositories remain JDBC-based.

## 简体中文

这是可执行的 Spring Boot 应用，负责装配 Data、Knowledge、Support、Report Copilot 和平台模块。

- 负责启动入口、运行配置、页面静态资源、Flyway 和数据库驱动。
- 不承载业务规则，业务代码放在 `modules/*`。
- 默认关闭 chat 和 embedding，通过根 README 中的环境变量显式启用。
- 当前通过 Report Copilot 引入 MyBatis-Plus Boot 4 starter；来源预览暂不落库，后续稳定报表 CRUD 可使用 MyBatis-Plus，动态 SQL 和 pgvector 仍使用 JDBC。

测试：

```bash
../../mvnw -f ../../pom.xml -pl app/business-copilot-app -am test
```
