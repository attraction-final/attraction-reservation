# CD가 실행 구성·모니터링 번들을 올리는 버킷 (계정·리전 네임스페이스 버킷)
resource "aws_s3_bucket" "artifacts" {
  bucket           = "attraction-reservation-${local.account_id}-${local.region}-an"
  bucket_namespace = "account-regional"
}

resource "aws_s3_bucket_public_access_block" "artifacts" {
  bucket                  = aws_s3_bucket.artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "artifacts" {
  bucket = aws_s3_bucket.artifacts.id

  rule {
    bucket_key_enabled       = true
    blocked_encryption_types = ["SSE-C"]

    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}
