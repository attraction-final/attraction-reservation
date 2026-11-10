resource "aws_lb" "main" {
  name               = "attraction-final"
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb.id]
  subnets            = [aws_subnet.public_2a.id, aws_subnet.public_2b.id]
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-Res-PQ-2025-09"
  certificate_arn   = data.aws_acm_certificate.wildcard.arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app_ecs.arn

    forward {
      target_group {
        arn = aws_lb_target_group.app_ecs.arn
      }
    }
  }
}

# grafana.xevbn.click: Cognito 인증을 거친 뒤 모니터링 EC2의 Grafana로 보낸다.
resource "aws_lb_listener_rule" "grafana" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10

  action {
    type = "authenticate-cognito"

    authenticate_cognito {
      user_pool_arn       = aws_cognito_user_pool.monitoring.arn
      user_pool_client_id = aws_cognito_user_pool_client.monitoring.id
      user_pool_domain    = aws_cognito_user_pool_domain.monitoring.domain
      session_timeout     = 28800
    }
  }

  action {
    type = "forward"

    forward {
      target_group {
        arn = aws_lb_target_group.grafana.arn
      }
      stickiness {
        enabled  = false
        duration = 3600
      }
    }
  }

  condition {
    host_header {
      values = ["grafana.xevbn.click"]
    }
  }

  tags = {
    Name = "grafana"
  }
}

# ECS api 서비스가 task IP를 등록한다.
resource "aws_lb_target_group" "app_ecs" {
  name                 = "attraction-app-ecs"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip"
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 60

  health_check {
    path                = "/readyz"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 5
    unhealthy_threshold = 2
  }
}

# 모니터링 EC2를 대상으로 등록한 것은 콘솔에서 했고 Terraform이 관리하지 않는다.
resource "aws_lb_target_group" "grafana" {
  name        = "attraction-grafana"
  port        = 3000
  protocol    = "HTTP"
  target_type = "instance"
  vpc_id      = aws_vpc.main.id

  health_check {
    path                = "/api/health"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 5
    unhealthy_threshold = 2
  }
}
