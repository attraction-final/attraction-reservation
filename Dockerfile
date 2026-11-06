# 운영 실행 이미지. jar는 CD에서 빌드하고 빌드 컨텍스트로 build/libs만 넘긴다.
#   docker build -f Dockerfile --build-arg JAR_FILE=<jar 파일명> build/libs

# 1) jar를 레이어별로 풀어 의존성 레이어가 버전마다 다시 올라가지 않게 한다.
FROM eclipse-temurin:17-jre-noble AS extract
WORKDIR /workspace
ARG JAR_FILE
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

# 2) 자주 바뀌지 않는 레이어부터 복사한다.
FROM eclipse-temurin:17-jre-noble
RUN useradd --system --no-create-home --uid 10001 app
WORKDIR /app
COPY --from=extract /workspace/extracted/dependencies/ ./
COPY --from=extract /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract /workspace/extracted/application/ ./
USER app

# 8080: 서비스, 8081: actuator (health, prometheus)
EXPOSE 8080 8081

# JAVA_TOOL_OPTIONS는 "Picked up ..." 줄을 logfmt가 아닌 형식으로 찍으므로 옵션을 직접 넘긴다.
# OOM이 나면 JVM을 끝내 컨테이너가 재시작되게 한다. (restart: unless-stopped)
# exec 형식이라 java가 PID 1이 되어 docker stop의 SIGTERM으로 graceful shutdown 된다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+ExitOnOutOfMemoryError", "org.springframework.boot.loader.launch.JarLauncher"]
