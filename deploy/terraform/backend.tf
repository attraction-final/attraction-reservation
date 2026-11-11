# state 버킷은 Terraform 밖에서 CLI로 만들었다 (버전 관리, SSE-S3, 퍼블릭 차단, TLS 강제)
terraform {
  backend "s3" {
    bucket       = "attraction-reservation-tfstate-793168138824"
    key          = "prod/terraform.tfstate"
    region       = "ap-northeast-2"
    encrypt      = true
    use_lockfile = true
  }
}
