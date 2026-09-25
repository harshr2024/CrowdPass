ephemeral "random_password" "database" {
  length           = 32
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

ephemeral "random_password" "jwt" {
  length  = 48
  special = false
}

ephemeral "random_password" "rate_limit" {
  length  = 48
  special = false
}

resource "aws_ssm_parameter" "database_username" {
  name        = local.parameter_names.database_username
  description = "CrowdPass temporary demo database username"
  type        = "SecureString"
  value       = var.database_username
}

resource "aws_ssm_parameter" "database_password" {
  name        = local.parameter_names.database_password
  description = "CrowdPass temporary demo database password"
  type        = "SecureString"

  value_wo         = ephemeral.random_password.database.result
  value_wo_version = var.database_password_generation
}

ephemeral "aws_ssm_parameter" "database_password" {
  arn             = aws_ssm_parameter.database_password.arn
  with_decryption = true
}

resource "aws_ssm_parameter" "jwt_secret" {
  name        = local.parameter_names.jwt_secret
  description = "CrowdPass temporary demo JWT signing secret"
  type        = "SecureString"

  value_wo         = base64encode(ephemeral.random_password.jwt.result)
  value_wo_version = var.jwt_secret_generation
}

resource "aws_ssm_parameter" "rate_limit_secret" {
  name        = local.parameter_names.rate_limit_secret
  description = "CrowdPass temporary demo rate-limit HMAC secret"
  type        = "SecureString"

  value_wo         = base64encode(ephemeral.random_password.rate_limit.result)
  value_wo_version = var.rate_limit_secret_generation
}
