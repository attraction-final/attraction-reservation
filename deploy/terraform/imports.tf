# 콘솔·CLI로 만든 기존 리소스를 state로 가져온다 (KAN-151).
# import가 끝난 뒤에도 남겨 두면 다시 import하지 않으므로 무해하다.

# 네트워크
import {
  to = aws_vpc.main
  id = "vpc-0283cebcecbda88c5"
}
import {
  to = aws_subnet.public_2a
  id = "subnet-07b2f1a78db9d8b4e"
}
import {
  to = aws_subnet.public_2b
  id = "subnet-0257c8d3ebd3f43d2"
}
import {
  to = aws_subnet.private_2a
  id = "subnet-0be2496d3931e4dc6"
}
import {
  to = aws_subnet.private_2b
  id = "subnet-008fc962259300add"
}
import {
  to = aws_internet_gateway.main
  id = "igw-07af4eb01fc62ebe0"
}
import {
  to = aws_eip.nat_2a
  id = "eipalloc-09a1d85f029c54424"
}
import {
  to = aws_nat_gateway.nat_2a
  id = "nat-0f57ee41a21afe804"
}
import {
  to = aws_route_table.public
  id = "rtb-07763342358b77936"
}
import {
  to = aws_route_table.private_2a
  id = "rtb-0157842a46ed64995"
}
import {
  to = aws_route_table.private_2b
  id = "rtb-0c81d7d353b18eb76"
}
import {
  to = aws_route_table_association.public_2a
  id = "subnet-07b2f1a78db9d8b4e/rtb-07763342358b77936"
}
import {
  to = aws_route_table_association.public_2b
  id = "subnet-0257c8d3ebd3f43d2/rtb-07763342358b77936"
}
import {
  to = aws_route_table_association.private_2a
  id = "subnet-0be2496d3931e4dc6/rtb-0157842a46ed64995"
}
import {
  to = aws_route_table_association.private_2b
  id = "subnet-008fc962259300add/rtb-0c81d7d353b18eb76"
}
import {
  to = aws_vpc_endpoint.s3
  id = "vpce-00680d801ec6caeac"
}
import {
  to = aws_vpc_endpoint_route_table_association.s3_private_2a
  id = "vpce-00680d801ec6caeac/rtb-0157842a46ed64995"
}
import {
  to = aws_vpc_endpoint_route_table_association.s3_private_2b
  id = "vpce-00680d801ec6caeac/rtb-0c81d7d353b18eb76"
}

# DNS
import {
  to = aws_route53_zone.public
  id = "Z07174983HMNK4GM9TEOB"
}
import {
  to = aws_route53_zone.internal
  id = "Z0261876YAS37FHXGKQ4"
}
import {
  to = aws_route53_record.api
  id = "Z07174983HMNK4GM9TEOB_api.xevbn.click_A"
}
import {
  to = aws_route53_record.grafana
  id = "Z07174983HMNK4GM9TEOB_grafana.xevbn.click_A"
}
import {
  to = aws_route53_record.acm_validation
  id = "Z07174983HMNK4GM9TEOB__23c36f8dce3fc762b9d440ece409d660.xevbn.click_CNAME"
}
import {
  to = aws_route53_record.loki
  id = "Z0261876YAS37FHXGKQ4_loki.attraction.internal_A"
}

# 보안 그룹
import {
  to = aws_security_group.alb
  id = "sg-036d52199ed4bb0d1"
}
import {
  to = aws_security_group.app
  id = "sg-0dfee6a3408712b64"
}
import {
  to = aws_security_group.monitoring
  id = "sg-095178c6c0b041649"
}
import {
  to = aws_security_group.rds
  id = "sg-0bb8a18cbb7fbbfa4"
}
import {
  to = aws_security_group.redis
  id = "sg-046a2df87baa920e4"
}
import {
  to = aws_vpc_security_group_ingress_rule.alb_http
  id = "sgr-0282d3200aaf74c4b"
}
import {
  to = aws_vpc_security_group_ingress_rule.alb_https
  id = "sgr-0369630961db657ec"
}
import {
  to = aws_vpc_security_group_egress_rule.alb_https_out
  id = "sgr-07410d8439002c446"
}
import {
  to = aws_vpc_security_group_egress_rule.alb_to_grafana
  id = "sgr-0cd7253d03211b5da"
}
import {
  to = aws_vpc_security_group_egress_rule.alb_to_app
  id = "sgr-0f91083a3918094f6"
}
import {
  to = aws_vpc_security_group_ingress_rule.app_from_alb_http
  id = "sgr-00c5d275515bbf755"
}
import {
  to = aws_vpc_security_group_ingress_rule.app_from_alb_actuator
  id = "sgr-0fca2b2c646a2fad0"
}
import {
  to = aws_vpc_security_group_egress_rule.app_all
  id = "sgr-063efb4949a3f9d4b"
}
import {
  to = aws_vpc_security_group_ingress_rule.monitoring_loki
  id = "sgr-046acf425601e57ad"
}
import {
  to = aws_vpc_security_group_ingress_rule.monitoring_prometheus
  id = "sgr-0a9164b57e5ce5f42"
}
import {
  to = aws_vpc_security_group_ingress_rule.monitoring_grafana
  id = "sgr-0af553b27d7a7fc2b"
}
import {
  to = aws_vpc_security_group_egress_rule.monitoring_all
  id = "sgr-0fe24fdff799ef136"
}
import {
  to = aws_vpc_security_group_ingress_rule.rds_from_app
  id = "sgr-0f01f2e4346517907"
}
import {
  to = aws_vpc_security_group_egress_rule.rds_all
  id = "sgr-06e6a0436bda1768e"
}
import {
  to = aws_vpc_security_group_ingress_rule.redis_from_app
  id = "sgr-0072b7927d9586998"
}
import {
  to = aws_vpc_security_group_egress_rule.redis_to_app
  id = "sgr-01e598a887eb86373"
}

# ALB
import {
  to = aws_lb.main
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:793168138824:loadbalancer/app/attraction-final/1d837d2395cf6f31"
}
import {
  to = aws_lb_listener.http
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:793168138824:listener/app/attraction-final/1d837d2395cf6f31/85d3eab2ce7eb8b6"
}
import {
  to = aws_lb_listener.https
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:793168138824:listener/app/attraction-final/1d837d2395cf6f31/6a36b6a5a2cf4017"
}
import {
  to = aws_lb_listener_rule.grafana
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:793168138824:listener-rule/app/attraction-final/1d837d2395cf6f31/6a36b6a5a2cf4017/7a6c98700056f8ed"
}
import {
  to = aws_lb_target_group.app_ecs
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:793168138824:targetgroup/attraction-app-ecs/215e2f37c84dd08a"
}
import {
  to = aws_lb_target_group.grafana
  id = "arn:aws:elasticloadbalancing:ap-northeast-2:793168138824:targetgroup/attraction-grafana/70022955af117502"
}

# Cognito (Grafana 앞단 인증)
import {
  to = aws_cognito_user_pool.monitoring
  id = "ap-northeast-2_wFoGsiJkB"
}
import {
  to = aws_cognito_user_pool_client.monitoring
  id = "ap-northeast-2_wFoGsiJkB/3avmjts7jotnd844qqj5gcj8m2"
}
import {
  to = aws_cognito_user_pool_domain.monitoring
  id = "ap-northeast-2wfogsijkb"
}

# ECS
import {
  to = aws_ecs_cluster.main
  id = "attraction-reservation"
}
import {
  to = aws_ecs_service.api
  id = "attraction-reservation/api"
}
import {
  to = aws_ecs_service.worker
  id = "attraction-reservation/worker"
}
import {
  to = aws_cloudwatch_log_group.ecs
  id = "/ecs/attraction-reservation"
}

# 데이터 저장소
import {
  to = aws_db_subnet_group.main
  id = "attraction-final-db-sng"
}
import {
  to = aws_db_instance.main
  id = "attraction-final-db"
}
import {
  to = aws_elasticache_subnet_group.main
  id = "attraction-final-cache-sng"
}
import {
  to = aws_elasticache_replication_group.main
  id = "attraction-final-cache"
}

# 모니터링 EC2
import {
  to = aws_instance.monitoring
  id = "i-0b91df092531d5b72"
}
import {
  to = aws_iam_instance_profile.monitoring
  id = "monitoring-ec2-role"
}

# IAM
import {
  to = aws_iam_openid_connect_provider.github
  id = "arn:aws:iam::793168138824:oidc-provider/token.actions.githubusercontent.com"
}
import {
  to = aws_iam_role.ecs_execution
  id = "attraction-ecs-execution-role"
}
import {
  to = aws_iam_role_policy.ecs_execution_read_app_secrets
  id = "attraction-ecs-execution-role:read-app-secrets"
}
import {
  to = aws_iam_role_policy_attachment.ecs_execution_managed
  id = "attraction-ecs-execution-role/arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}
import {
  to = aws_iam_role.ecs_task
  id = "attraction-ecs-task-role"
}
import {
  to = aws_iam_role_policy.ecs_task_exec
  id = "attraction-ecs-task-role:ecs-exec"
}
import {
  to = aws_iam_role.github_actions
  id = "github-actions-role"
}
import {
  to = aws_iam_role_policy.github_actions_deploy_ecs
  id = "github-actions-role:cd-deploy-ecs"
}
import {
  to = aws_iam_role_policy.github_actions_deploy_monitoring
  id = "github-actions-role:cd-deploy-monitoring"
}
import {
  to = aws_iam_role_policy.github_actions_deploy_bundle_s3
  id = "github-actions-role:cd-deploy-bundle-s3"
}
import {
  to = aws_iam_role_policy_attachment.github_actions_ecr
  id = "github-actions-role/arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser"
}
import {
  to = aws_iam_role.monitoring
  id = "monitoring-ec2-role"
}
import {
  to = aws_iam_role_policy.monitoring_read
  id = "monitoring-ec2-role:monitoring-read"
}
import {
  to = aws_iam_role_policy_attachment.monitoring_ssm
  id = "monitoring-ec2-role/arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# ECR, S3
import {
  to = aws_ecr_repository.app
  id = "attraction-reservation"
}
import {
  to = aws_ecr_repository.fluent_bit
  id = "attraction-reservation-fluent-bit"
}
import {
  to = aws_ecr_repository.alloy
  id = "attraction-reservation-alloy"
}
import {
  to = aws_s3_bucket.artifacts
  id = "attraction-reservation-793168138824-ap-northeast-2-an"
}
import {
  to = aws_s3_bucket_public_access_block.artifacts
  id = "attraction-reservation-793168138824-ap-northeast-2-an"
}
import {
  to = aws_s3_bucket_server_side_encryption_configuration.artifacts
  id = "attraction-reservation-793168138824-ap-northeast-2-an"
}
