resource "aws_ecs_cluster" "main" {
  name = "attraction-reservation"

  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

# 사이드카(log-router, alloy) 자체 로그. app 로그는 Fluent Bit이 Loki로 보낸다.
resource "aws_cloudwatch_log_group" "ecs" {
  name              = "/ecs/attraction-reservation"
  retention_in_days = 7
}

# task definition은 CD가 deploy/ecs/*-task-definition.json으로 등록하고 서비스를 갱신한다.
# Terraform은 서비스 설정만 관리하고 task_definition 값은 따라가지 않는다.

resource "aws_ecs_service" "api" {
  name                          = "api"
  cluster                       = aws_ecs_cluster.main.arn
  task_definition               = "attraction-reservation-api"
  desired_count                 = 1
  launch_type                   = "FARGATE"
  platform_version              = "LATEST"
  availability_zone_rebalancing = "ENABLED"
  enable_execute_command        = true
  propagate_tags                = "SERVICE"

  # 새 task가 healthy가 된 뒤 이전 task를 내린다.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  # Flyway를 포함한 기동 시간
  health_check_grace_period_seconds = 120

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.app_ecs.arn
    container_name   = "app"
    container_port   = 8080
  }

  network_configuration {
    subnets          = [aws_subnet.private_2a.id, aws_subnet.private_2b.id]
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = false
  }

  tags = {
    project = "attraction-reservation"
  }

  lifecycle {
    ignore_changes = [task_definition]
  }
}

# 스케줄러를 실행한다. 스케줄러가 겹치지 않도록 이전 task를 내린 뒤 새 task를 띄운다.
resource "aws_ecs_service" "worker" {
  name                          = "worker"
  cluster                       = aws_ecs_cluster.main.arn
  task_definition               = "attraction-reservation-worker"
  desired_count                 = 1
  launch_type                   = "FARGATE"
  platform_version              = "LATEST"
  availability_zone_rebalancing = "DISABLED"
  enable_execute_command        = true
  propagate_tags                = "SERVICE"

  deployment_minimum_healthy_percent = 0
  deployment_maximum_percent         = 100

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = [aws_subnet.private_2a.id, aws_subnet.private_2b.id]
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = false
  }

  tags = {
    project = "attraction-reservation"
  }

  lifecycle {
    ignore_changes = [task_definition]
  }
}
