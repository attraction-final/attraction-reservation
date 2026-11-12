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

  # 헬스체크는 통과했지만 5xx가 급증하는 배포를 되돌린다. 알람은 배포 중에만 본다.
  alarms {
    enable      = true
    rollback    = true
    alarm_names = [aws_cloudwatch_metric_alarm.api_5xx.alarm_name]
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

  # task 수는 아래 오토스케일링이 바꾼다.
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
}

# api 배포 롤백용 알람. 앱이 낸 5xx가 1분에 5건 이상이면서 요청의 5%를 넘는 분이
# 최근 3분 중 2분이면 ALARM이 된다. 한 번 튄 5xx로는 롤백하지 않는다.
# 사람에게 보내는 알림은 Grafana(api 5xx)가 맡으므로 alarm_actions는 두지 않는다.
# 5분 창을 쓰지 않는 이유: 배포가 끝난 뒤 ECS가 알람을 지켜보는 시간(bake time)이 5분이 채 안 된다.
resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name          = "attraction-reservation-api-5xx"
  alarm_description   = "api 배포 중 5xx 급증 시 ECS가 이전 리비전으로 롤백한다"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  threshold           = 1
  evaluation_periods  = 3
  datapoints_to_alarm = 2
  treat_missing_data  = "notBreaching"

  metric_query {
    id          = "breach"
    expression  = "IF(errors >= 5 AND errors / requests > 0.05, 1, 0)"
    label       = "5xx 급증"
    return_data = true
  }

  metric_query {
    id = "errors"

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "HTTPCode_Target_5XX_Count"
      stat        = "Sum"
      period      = 60
      dimensions = {
        LoadBalancer = aws_lb.main.arn_suffix
        TargetGroup  = aws_lb_target_group.app_ecs.arn_suffix
      }
    }
  }

  metric_query {
    id = "requests"

    metric {
      namespace   = "AWS/ApplicationELB"
      metric_name = "RequestCount"
      stat        = "Sum"
      period      = 60
      dimensions = {
        LoadBalancer = aws_lb.main.arn_suffix
        TargetGroup  = aws_lb_target_group.app_ecs.arn_suffix
      }
    }
  }
}

# api task 수를 CPU 사용률에 맞춰 1~3개로 조정한다. worker는 스케줄러가 겹치지 않도록 1개로 고정한다.
resource "aws_appautoscaling_target" "api" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.main.name}/${aws_ecs_service.api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = 1
  max_capacity       = 3
}

resource "aws_appautoscaling_policy" "api_cpu" {
  name               = "api-cpu-target-tracking"
  service_namespace  = aws_appautoscaling_target.api.service_namespace
  resource_id        = aws_appautoscaling_target.api.resource_id
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension
  policy_type        = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    target_value = 60

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }

    # 부하가 잠깐 내려간 것으로 task를 내리지 않도록 줄이는 쪽을 더 오래 기다린다.
    scale_out_cooldown = 60
    scale_in_cooldown  = 300
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
