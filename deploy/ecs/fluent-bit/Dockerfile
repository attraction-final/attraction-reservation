# ECS FireLens 로그 라우터. Fargate는 사용자 설정을 이미지 안 파일로만 받으므로 설정을 넣어 만든다.
# task definition의 firelensConfiguration이 /fluent-bit/etc/extra.conf를 가리킨다.
FROM public.ecr.aws/aws-observability/aws-for-fluent-bit:3.5.4
COPY extra.conf /fluent-bit/etc/extra.conf
COPY parsers.conf /fluent-bit/etc/app-parsers.conf
