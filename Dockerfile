# 多阶段构建：先用 Maven 构建可执行 jar，再用精简 JRE 运行。
# docker build -t spring-ai-business-copilot .
# 也可直接通过 examples/docker-compose.yml 一键启动（会自动构建）。

# ---------- 构建阶段 ----------
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# 先拷贝 pom 利用 Docker 层缓存加速依赖解析
COPY pom.xml ./
COPY platform/ platform/
COPY modules/ modules/
COPY app/ app/

# 构建 app 模块的可执行 jar（跳过测试以加速镜像构建）。
# BuildKit 缓存 Maven 仓库，网络抖动重试和后续构建都可以复用已下载依赖。
RUN --mount=type=cache,id=maven-repository,target=/root/.m2/repository \
    mvn -q -B clean package -DskipTests -pl app/business-copilot-app -am

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# 先安装 Alpine 已发布的安全更新；curl 仅用于容器健康检查。
# 应用进程使用固定的无特权 UID/GID。
RUN apk upgrade --no-cache \
    && apk add --no-cache curl \
    && addgroup -g 10001 -S app \
    && adduser -u 10001 -S -D -H -G app app

# 拷贝构建产物，并确保运行用户只需要读取应用文件。
COPY --from=builder --chown=10001:10001 \
    /build/app/business-copilot-app/target/business-copilot-app-*.jar app.jar

# 通过环境变量读取配置（datasource、Spring AI 等）
ENV JAVA_OPTS=""
EXPOSE 8080

USER 10001:10001
STOPSIGNAL SIGTERM
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD curl --fail --silent --show-error "http://127.0.0.1:${PORT:-8080}/actuator/health" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
