# Grafana 앞단 인증. 셀프 가입을 끄고 관리자가 만든 사용자만 로그인한다.
resource "aws_cognito_user_pool" "monitoring" {
  name                     = "User pool - k9tyjg"
  deletion_protection      = "ACTIVE"
  user_pool_tier           = "ESSENTIALS"
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
    recovery_mechanism {
      name     = "verified_phone_number"
      priority = 2
    }
  }

  password_policy {
    minimum_length                   = 8
    require_lowercase                = true
    require_numbers                  = true
    require_symbols                  = true
    require_uppercase                = true
    temporary_password_validity_days = 7
  }

  sign_in_policy {
    allowed_first_auth_factors = ["PASSWORD"]
  }

  username_configuration {
    case_sensitive = false
  }
}

resource "aws_cognito_user_pool_client" "monitoring" {
  name         = "attraction-monitoring"
  user_pool_id = aws_cognito_user_pool.monitoring.id

  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["email", "openid", "phone"]
  callback_urls                        = ["https://grafana.xevbn.click/oauth2/idpresponse"]
  supported_identity_providers         = ["COGNITO"]
  explicit_auth_flows                  = ["ALLOW_REFRESH_TOKEN_AUTH", "ALLOW_USER_AUTH", "ALLOW_USER_SRP_AUTH"]
  prevent_user_existence_errors        = "ENABLED"
  enable_token_revocation              = true

  auth_session_validity  = 3
  access_token_validity  = 60
  id_token_validity      = 60
  refresh_token_validity = 5

  token_validity_units {
    access_token  = "minutes"
    id_token      = "minutes"
    refresh_token = "days"
  }
}

resource "aws_cognito_user_pool_domain" "monitoring" {
  domain                = "ap-northeast-2wfogsijkb"
  user_pool_id          = aws_cognito_user_pool.monitoring.id
  managed_login_version = 2
}
