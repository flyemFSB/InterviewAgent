# ==============================================================================
# 第一阶段：构建环境 (Builder Stage)
# 作用：使用包含 JDK 的镜像 + Maven Wrapper 编译源代码
# ==============================================================================
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace

# 1. 优先复制 Maven 构建配置文件
# 技巧：利用 Docker 缓存层机制。
# 只要 pom.xml / .mvn 不变，这一层就会被缓存，从而跳过耗时的依赖下载步骤。
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY app/pom.xml app/
# Windows 上生成的文件没有可执行位，显式补上
RUN chmod +x mvnw

# 2. 预下载依赖 (Dependency Resolution)
# 作用：只要 pom 不变，这一层缓存就会生效。
#
# 参数详解：
# -B   : batch mode，去掉交互式进度条（CI/容器里的最佳实践）
# -ntp : 不打印下载进度，日志更干净
# || true: 兜底策略。预下载失败（例如某个库临时不可达）也不打断构建，
#          后面 package 会再检查并下载一次。
RUN ./mvnw -B -ntp -pl app dependency:go-offline || true

# 3. 复制源代码
# 将整个项目复制进来。因为依赖已在上一层缓存，即使源码变化也不会重新下载。
COPY . .

# 4. 执行构建
# -pl app            -> 只构建 app 模块
# -Dmaven.test.skip  -> 跳过测试编译与执行（加快构建；测试由 CI 负责）
RUN ./mvnw -B -ntp -pl app clean package -Dmaven.test.skip=true

# ==============================================================================
# 第二阶段：运行环境 (Runtime Stage)
# 作用：使用轻量级的 JRE 镜像来运行编译好的 JAR 包
# 技巧：多阶段构建 (Multi-stage build) 可以显著减小最终镜像的体积
# ==============================================================================
FROM eclipse-temurin:25-jre
WORKDIR /app

# 从第一阶段 (builder) 中复制生成的 JAR 文件到当前镜像
# 只复制构建产物，不复制源代码和 Maven 本地仓库，保持镜像干净
COPY --from=builder /workspace/app/target/app-*.jar app.jar

# 声明容器将暴露的端口（仅作为文档说明，实际映射需在 docker-compose 中配置）
EXPOSE 8080

# 容器启动时执行的命令
ENTRYPOINT ["java", "-jar", "app.jar"]
