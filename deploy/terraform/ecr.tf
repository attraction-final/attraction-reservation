# 태그는 커밋 SHA이고 모두 IMMUTABLE이다. 수동 롤백은 남아 있는 이미지 안에서만 할 수 있다.

resource "aws_ecr_repository" "app" {
  name                 = "attraction-reservation"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = false
  }
}

resource "aws_ecr_repository" "fluent_bit" {
  name                 = "attraction-reservation-fluent-bit"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_repository" "alloy" {
  name                 = "attraction-reservation-alloy"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

# 태그된 이미지만 센다. buildx로 올린 이미지는 태그된 index 아래 플랫폼 이미지가 태그 없이 들어 있어서,
# 태그 없는 이미지를 지우는 규칙을 두면 실행 중인 이미지가 깨질 수 있다.
locals {
  ecr_lifecycle_policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep only the latest 30 tagged images"
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = 30
        }
        action = { type = "expire" }
      },
    ]
  })
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy     = local.ecr_lifecycle_policy
}

resource "aws_ecr_lifecycle_policy" "fluent_bit" {
  repository = aws_ecr_repository.fluent_bit.name
  policy     = local.ecr_lifecycle_policy
}

resource "aws_ecr_lifecycle_policy" "alloy" {
  repository = aws_ecr_repository.alloy.name
  policy     = local.ecr_lifecycle_policy
}
