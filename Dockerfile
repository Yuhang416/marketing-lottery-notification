# 构建阶段：用项目自带的 mvnw 打包（pom 里默认跳过测试）
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q package

# 运行阶段：只带 JRE 和打好的 jar
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/lottery-system-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
