# 콘솔에서 만든 설정을 그대로 옮겼다. 아래 값을 바꾸면 운영 DB·캐시에 바로 반영되므로 plan을 꼭 확인한다.

resource "aws_db_subnet_group" "private" {
  name        = "attraction-final-db-private-sng"
  description = "attraction-final-db private subnets"
  subnet_ids  = [aws_subnet.private_2a.id, aws_subnet.private_2b.id]
}

# 꺼 둘 때(paused) 최종 스냅샷을 남기고 지운다. 다시 켤 때 같은 identifier로 스냅샷에서 복원하므로
# 엔드포인트와 비밀번호가 그대로다.
resource "aws_db_instance" "main" {
  count          = var.paused ? 0 : 1
  identifier     = "attraction-final-db"
  engine         = "mysql"
  engine_version = "8.4.11"
  instance_class = "db.t4g.micro"

  db_name  = "attraction_reservation"
  username = "admin"
  # 비밀번호는 Terraform이 관리하지 않는다 (state에 평문으로 남지 않게).
  # 그래서 새로 만들 수는 없고 스냅샷 복원으로만 다시 켠다.
  snapshot_identifier = var.db_snapshot_identifier

  allocated_storage     = 20
  max_allocated_storage = 1000
  storage_type          = "gp2"
  storage_encrypted     = true
  kms_key_id            = "arn:aws:kms:${local.region}:${local.account_id}:key/baff3704-7521-4ee9-8fa6-05429f87a52b"

  db_subnet_group_name   = aws_db_subnet_group.private.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  parameter_group_name     = "default.mysql8.4"
  option_group_name        = "default:mysql-8-4"
  engine_lifecycle_support = "open-source-rds-extended-support-disabled"

  backup_retention_period   = 7
  backup_window             = "16:54-17:24"
  maintenance_window        = "mon:14:31-mon:15:01"
  copy_tags_to_snapshot     = true
  deletion_protection       = var.db_deletion_protection
  skip_final_snapshot       = false
  final_snapshot_identifier = var.db_final_snapshot_identifier
  # 변경을 maintenance window까지 미루지 않는다. 백업 0 → 7 같은 변경은 짧은 재시작이 생길 수 있다.
  apply_immediately = true

  # 복원에 쓴 스냅샷 이름이 바뀌면 인스턴스를 다시 만드므로, 생성할 때만 쓴다.
  lifecycle {
    ignore_changes = [snapshot_identifier]
  }
}

resource "aws_elasticache_subnet_group" "main" {
  name        = "attraction-final-cache-sng"
  description = "attraction-final-redis-sng"
  subnet_ids  = [aws_subnet.private_2a.id, aws_subnet.private_2b.id]
}

# 꺼 둘 때(paused) 스냅샷 없이 지운다. 캐시, 리프레시 토큰, 소셜 로그인 코드만 있어서
# 다시 켜면 사용자가 다시 로그인하면 된다.
resource "aws_elasticache_replication_group" "main" {
  count                = var.paused ? 0 : 1
  replication_group_id = "attraction-final-cache"
  description          = "attraction-final-redis"
  engine               = "redis"
  engine_version       = "7.1"
  node_type            = "cache.t4g.micro"
  num_cache_clusters   = 1
  parameter_group_name = "default.redis7"
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.redis.id]

  automatic_failover_enabled = false
  multi_az_enabled           = false

  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  transit_encryption_mode    = "preferred"

  maintenance_window       = "sat:08:30-sat:09:30"
  snapshot_retention_limit = 1
  snapshot_window          = "01:30-02:30"
}

# 다시 켠 뒤 SSM env/REDIS_HOST와 같은지 확인한다.
output "redis_primary_endpoint" {
  value = one(aws_elasticache_replication_group.main[*].primary_endpoint_address)
}
