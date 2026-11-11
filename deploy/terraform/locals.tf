locals {
  account_id = "793168138824"
  region     = "ap-northeast-2"

  # 앱 설정·CD 기준 버전이 들어 있는 Parameter Store 경로. 값은 Terraform이 관리하지 않는다.
  ssm_prefix_arn = "arn:aws:ssm:${local.region}:${local.account_id}:parameter/attraction-reservation/prod"

  # CD가 실행 구성·모니터링 번들을 올리는 S3 접두사
  bundle_prefix = "attraction-reservation"
}
