# ECS task의 메트릭 수집기. Fargate는 호스트 파일을 마운트할 수 없으므로 설정을 넣어 만든다.
FROM grafana/alloy:v1.21.3
COPY config.alloy /etc/alloy/config.alloy
CMD ["run", "--server.http.listen-addr=127.0.0.1:12345", "--storage.path=/var/lib/alloy/data", "/etc/alloy/config.alloy"]
