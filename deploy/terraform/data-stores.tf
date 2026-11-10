# 콘솔에서 만든 설정을 그대로 옮겼다. 아래 값을 바꾸면 운영 DB·캐시에 바로 반영되므로 plan을 꼭 확인한다.

resource "aws_db_subnet_group" "main" {
  name        = "attraction-final-db-sng"
  description = "attration-final-db-sng"
  subnet_ids  = [aws_subnet.public_2a.id, aws_subnet.public_2b.id]
}

resource "aws_db_instance" "main" {
  identifier     = "attraction-final-db"
  engine         = "mysql"
  engine_version = "8.4.11"
  instance_class = "db.t4g.micro"

  db_name  = "attraction_reservation"
  username = "admin"
  # 비밀번호는 Terraform이 관리하지 않는다 (state에 평문으로 남지 않게).

  allocated_storage     = 20
  max_allocated_storage = 1000
  storage_type          = "gp2"
  storage_encrypted     = true
  kms_key_id            = "arn:aws:kms:${local.region}:${local.account_id}:key/baff3704-7521-4ee9-8fa6-05429f87a52b"

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false

  parameter_group_name     = "default.mysql8.4"
  option_group_name        = "default:mysql-8-4"
  engine_lifecycle_support = "open-source-rds-extended-support-disabled"

  backup_retention_period = 0
  backup_window           = "16:54-17:24"
  maintenance_window      = "mon:14:31-mon:15:01"
  copy_tags_to_snapshot   = true
  skip_final_snapshot     = true
}

resource "aws_elasticache_subnet_group" "main" {
  name        = "attraction-final-cache-sng"
  description = "attraction-final-redis-sng"
  subnet_ids  = [aws_subnet.private_2a.id, aws_subnet.private_2b.id]
}

resource "aws_elasticache_replication_group" "main" {
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
