# 비용 때문에 인프라를 잠시 꺼 둘 때 쓰는 값. 절차는 docs/infra/pause-resume.md를 따른다.

variable "paused" {
  description = "true면 시간당 비용이 드는 리소스(NAT, ALB, ECS 서비스, RDS, ElastiCache)를 지우고 모니터링 EC2를 중지한다."
  type        = bool
  default     = false
}

# 삭제 보호와 최종 스냅샷 이름은 삭제할 때 state에 있는 값을 쓴다.
# 그래서 paused=true로 지우기 전에 이 두 값을 먼저 apply해 둬야 한다.
variable "db_deletion_protection" {
  description = "RDS 삭제 보호. 꺼 두기 직전에만 false로 apply한다."
  type        = bool
  default     = true
}

variable "db_final_snapshot_identifier" {
  description = "RDS를 지울 때 남길 최종 스냅샷 이름. 같은 이름이 이미 있으면 삭제가 실패한다."
  type        = string
  default     = "attraction-final-db-final"
}

variable "db_snapshot_identifier" {
  description = "다시 켤 때 RDS를 복원할 스냅샷 이름. 생성할 때만 쓰이고 이후 변경은 무시한다."
  type        = string
  default     = null
}
