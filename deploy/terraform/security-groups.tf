# 규칙은 SG 안에 inline으로 쓰지 않는다. ALB SG와 app SG가 서로를 참조해 순환이 생기기 때문이다.
# SG 이름·설명은 EC2 시절 그대로다. 설명을 바꾸면 SG를 다시 만들어야 하므로 두고 쓴다.

resource "aws_security_group" "alb" {
  name        = "attraction-final"
  description = "attraction-final alb"
  vpc_id      = aws_vpc.main.id
}

resource "aws_security_group" "app" {
  name        = "attraction-final-app-sg"
  description = "server-instance-sg"
  vpc_id      = aws_vpc.main.id
}

resource "aws_security_group" "monitoring" {
  name        = "attraction-final-monitoring-sg"
  description = "Monitoring: Loki and Prometheus from app instances"
  vpc_id      = aws_vpc.main.id
}

resource "aws_security_group" "rds" {
  name        = "attraction-final-rds-sg"
  description = "attraction-final-mysql-sg"
  vpc_id      = aws_vpc.main.id
}

resource "aws_security_group" "redis" {
  name        = "attraction-final-redis-sg"
  description = "attraction-final-redis-sg"
  vpc_id      = aws_vpc.main.id
}

# ALB
resource "aws_vpc_security_group_ingress_rule" "alb_http" {
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

# Cognito 인증 단계에서 ALB가 Cognito 토큰 엔드포인트를 호출한다.
resource "aws_vpc_security_group_egress_rule" "alb_https_out" {
  security_group_id = aws_security_group.alb.id
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "alb_to_app" {
  security_group_id            = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8081
  referenced_security_group_id = aws_security_group.app.id
}

resource "aws_vpc_security_group_egress_rule" "alb_to_grafana" {
  security_group_id            = aws_security_group.alb.id
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
  referenced_security_group_id = aws_security_group.monitoring.id
}

# app (ECS task)
resource "aws_vpc_security_group_ingress_rule" "app_from_alb_http" {
  security_group_id            = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.alb.id
}

resource "aws_vpc_security_group_ingress_rule" "app_from_alb_actuator" {
  security_group_id            = aws_security_group.app.id
  ip_protocol                  = "tcp"
  from_port                    = 8081
  to_port                      = 8081
  referenced_security_group_id = aws_security_group.alb.id
}

resource "aws_vpc_security_group_egress_rule" "app_all" {
  security_group_id = aws_security_group.app.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# 모니터링 EC2
resource "aws_vpc_security_group_ingress_rule" "monitoring_loki" {
  security_group_id            = aws_security_group.monitoring.id
  description                  = "Loki push from app"
  ip_protocol                  = "tcp"
  from_port                    = 3100
  to_port                      = 3100
  referenced_security_group_id = aws_security_group.app.id
}

resource "aws_vpc_security_group_ingress_rule" "monitoring_prometheus" {
  security_group_id            = aws_security_group.monitoring.id
  description                  = "Prometheus remote_write from app"
  ip_protocol                  = "tcp"
  from_port                    = 9090
  to_port                      = 9090
  referenced_security_group_id = aws_security_group.app.id
}

resource "aws_vpc_security_group_ingress_rule" "monitoring_grafana" {
  security_group_id            = aws_security_group.monitoring.id
  ip_protocol                  = "tcp"
  from_port                    = 3000
  to_port                      = 3000
  referenced_security_group_id = aws_security_group.alb.id
}

resource "aws_vpc_security_group_egress_rule" "monitoring_all" {
  security_group_id = aws_security_group.monitoring.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# RDS
resource "aws_vpc_security_group_ingress_rule" "rds_from_app" {
  security_group_id            = aws_security_group.rds.id
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  referenced_security_group_id = aws_security_group.app.id
}

resource "aws_vpc_security_group_egress_rule" "rds_all" {
  security_group_id = aws_security_group.rds.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ElastiCache
resource "aws_vpc_security_group_ingress_rule" "redis_from_app" {
  security_group_id            = aws_security_group.redis.id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = aws_security_group.app.id
}

resource "aws_vpc_security_group_egress_rule" "redis_to_app" {
  security_group_id            = aws_security_group.redis.id
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = aws_security_group.app.id
}
