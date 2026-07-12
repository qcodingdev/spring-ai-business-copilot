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

# 构建 app 模块的可执行 jar（跳过测试以加速镜像构建）
RUN mvn -q -B clean package -DskipTests -pl app/business-copilot-app -am

# ---------- 运行阶段 ----------
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 拷贝构建产物
COPY --from=builder /build/app/business-copilot-app/target/business-copilot-app-*.jar app.jar

# 通过环境变量读取配置（datasource、Spring AI 等）
ENV JAVA_OPTS=""
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
