# Prometheus, Loki, Grafana를 docker compose로 실행한다 (deploy/monitoring/).
# 사설 IP는 Route 53 loki 레코드와 SSM PROMETHEUS_URL이 가리키므로 고정한다.
resource "aws_instance" "monitoring" {
  ami                    = "ami-029fa37c14d9a8917"
  instance_type          = "t4g.medium"
  subnet_id              = aws_subnet.private_2a.id
  private_ip             = "10.0.130.10"
  vpc_security_group_ids = [aws_security_group.monitoring.id]
  iam_instance_profile   = aws_iam_instance_profile.monitoring.name

  # Grafana 컨테이너가 인스턴스 역할로 CloudWatch를 읽도록 hop limit을 2로 둔다.
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  credit_specification {
    cpu_credits = "unlimited"
  }

  # /data가 루트 볼륨에 있다. 인스턴스를 지워도 볼륨은 남긴다.
  root_block_device {
    volume_type           = "gp3"
    volume_size           = 30
    iops                  = 3000
    throughput            = 125
    encrypted             = true
    kms_key_id            = "arn:aws:kms:${local.region}:${local.account_id}:key/6e012d15-243f-42c8-ad97-ab0dc61502fd"
    delete_on_termination = false

    tags = {
      Name = "attraction-reservation-monitoring-data"
    }
  }

  tags = {
    Name = "attraction-reservation-monitoring"
  }

  # ami, user_data가 바뀌면 인스턴스를 다시 만든다. 기동 스크립트는 deploy/monitoring/user-data.sh가 원본이다.
  lifecycle {
    ignore_changes = [ami, user_data]
  }
}

resource "aws_iam_instance_profile" "monitoring" {
  name = "monitoring-ec2-role"
  role = aws_iam_role.monitoring.name
}
