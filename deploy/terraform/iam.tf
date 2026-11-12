resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["ab9d0263244dd0326eb67015705a667e79cfe998"]
}

# ECS가 task를 띄울 때 쓰는 역할: ECR pull, CloudWatch Logs, SSM 파라미터 읽기
resource "aws_iam_role" "ecs_execution" {
  name        = "attraction-ecs-execution-role"
  description = "KAN-127 ECS task execution role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ecs_execution_read_app_secrets" {
  name = "read-app-secrets"
  role = aws_iam_role.ecs_execution.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "ssm:GetParameters"
      Resource = ["${local.ssm_prefix_arn}/env/*", "${local.ssm_prefix_arn}/PROMETHEUS_URL"]
    }]
  })
}

# task 안에서 쓰는 역할: ECS Exec만 허용한다. 앱 코드는 AWS API를 호출하지 않는다.
resource "aws_iam_role" "ecs_task" {
  name        = "attraction-ecs-task-role"
  description = "KAN-127 ECS task role (ECS Exec)"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
      }
    }]
  })
}

resource "aws_iam_role_policy" "ecs_task_exec" {
  name = "ecs-exec"
  role = aws_iam_role.ecs_task.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "ssmmessages:CreateControlChannel",
        "ssmmessages:CreateDataChannel",
        "ssmmessages:OpenControlChannel",
        "ssmmessages:OpenDataChannel",
      ]
      Resource = "*"
    }]
  })
}

# CD(GitHub Actions)가 OIDC로 맡는 역할. production Environment의 job만 맡을 수 있다.
# 저장소는 불변 ID 형식의 OIDC sub를 쓴다.
resource "aws_iam_role" "github_actions" {
  name        = "github-actions-role"
  description = "attraction-final"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = "repo:attraction-final@342808963/attraction-reservation@1421542612:environment:production"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "github_actions_ecr" {
  role       = aws_iam_role.github_actions.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser"
}

resource "aws_iam_role_policy" "github_actions_deploy_ecs" {
  name = "cd-deploy-ecs"
  role = aws_iam_role.github_actions.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "AppVersionParameter"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:PutParameter"]
        Resource = "${local.ssm_prefix_arn}/APP_VERSION"
      },
      {
        Sid      = "TaskDefinitions"
        Effect   = "Allow"
        Action   = ["ecs:RegisterTaskDefinition", "ecs:DescribeTaskDefinition"]
        Resource = "*"
      },
      {
        Sid      = "Services"
        Effect   = "Allow"
        Action   = ["ecs:UpdateService", "ecs:DescribeServices"]
        Resource = "arn:aws:ecs:${local.region}:${local.account_id}:service/${aws_ecs_cluster.main.name}/*"
      },
      {
        Sid      = "PassTaskRoles"
        Effect   = "Allow"
        Action   = "iam:PassRole"
        Resource = [aws_iam_role.ecs_execution.arn, aws_iam_role.ecs_task.arn]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
    ]
  })
}

resource "aws_iam_role_policy" "github_actions_deploy_monitoring" {
  name = "cd-deploy-monitoring"
  role = aws_iam_role.github_actions.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "VersionParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter", "ssm:PutParameter"]
        Resource = "${local.ssm_prefix_arn}/MONITORING_VERSION"
      },
      {
        Sid      = "RunCommandOnMonitoringInstance"
        Effect   = "Allow"
        Action   = "ssm:SendCommand"
        Resource = "arn:aws:ec2:${local.region}:${local.account_id}:instance/*"
        Condition = {
          StringEquals = { "ssm:resourceTag/Name" = "attraction-reservation-monitoring" }
        }
      },
      {
        Sid      = "RunShellScriptDocument"
        Effect   = "Allow"
        Action   = "ssm:SendCommand"
        Resource = "arn:aws:ssm:${local.region}::document/AWS-RunShellScript"
      },
      {
        Sid      = "CommandResult"
        Effect   = "Allow"
        Action   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations"]
        Resource = "*"
      },
      {
        Sid      = "FindMonitoringInstance"
        Effect   = "Allow"
        Action   = "ec2:DescribeInstances"
        Resource = "*"
      },
    ]
  })
}

resource "aws_iam_role_policy" "github_actions_deploy_bundle_s3" {
  name = "cd-deploy-bundle-s3"
  role = aws_iam_role.github_actions.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "DeployBundleObjects"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.artifacts.arn}/${local.bundle_prefix}/*"
      },
      {
        Sid      = "DeployBundleList"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.artifacts.arn
        Condition = {
          StringLike = { "s3:prefix" = "${local.bundle_prefix}/*" }
        }
      },
    ]
  })
}

# 모니터링 EC2: 모니터링 번들 읽기, SSM 파라미터 읽기, Grafana의 CloudWatch 조회
resource "aws_iam_role" "monitoring" {
  name        = "monitoring-ec2-role"
  description = "Monitoring instance: Prometheus, Loki, Grafana"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "monitoring_ssm" {
  role       = aws_iam_role.monitoring.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "monitoring_read" {
  name = "monitoring-read"
  role = aws_iam_role.monitoring.name

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "DeployBundleRead"
        Effect   = "Allow"
        Action   = "s3:GetObject"
        Resource = "${aws_s3_bucket.artifacts.arn}/${local.bundle_prefix}/*"
      },
      {
        Sid      = "DeployBundleList"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.artifacts.arn
        Condition = {
          StringLike = { "s3:prefix" = "${local.bundle_prefix}/*" }
        }
      },
      {
        Sid      = "ParameterRead"
        Effect   = "Allow"
        Action   = "ssm:GetParameter"
        Resource = ["${local.ssm_prefix_arn}/MONITORING_VERSION", "${local.ssm_prefix_arn}/monitoring/*"]
      },
      {
        Sid    = "CloudWatchMetricsRead"
        Effect = "Allow"
        Action = [
          "cloudwatch:GetMetricData",
          "cloudwatch:ListMetrics",
          "cloudwatch:GetMetricStatistics",
          "cloudwatch:DescribeAlarms",
          "cloudwatch:DescribeAlarmsForMetric",
          "cloudwatch:DescribeAlarmHistory",
        ]
        Resource = "*"
      },
      {
        Sid      = "GrafanaResourceLookup"
        Effect   = "Allow"
        Action   = ["ec2:DescribeRegions", "ec2:DescribeInstances", "ec2:DescribeTags", "tag:GetResources"]
        Resource = "*"
      },
    ]
  })
}
