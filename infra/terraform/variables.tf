variable "aws_region" {
  description = "AWS region for the temporary CrowdPass demo stack."
  type        = string
  default     = "us-west-2"

  validation {
    condition     = var.aws_region == "us-west-2"
    error_message = "The verified temporary demo architecture is restricted to us-west-2."
  }
}

variable "expires_at" {
  description = "Optional fixed RFC3339 UTC expiry used for survivor audits. Set it once and keep it stable for the stack lifetime."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition     = var.expires_at == null || can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", var.expires_at))
    error_message = "expires_at must be null or a fixed RFC3339 UTC timestamp such as 2026-09-25T04:00:00Z."
  }
}

variable "existing_github_oidc_provider_arn" {
  description = "Existing account-level GitHub Actions OIDC provider ARN to reuse. Null makes CrowdPass create and own the provider."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = (
      var.existing_github_oidc_provider_arn == null ||
      can(regex("^arn:aws:iam::[0-9]{12}:oidc-provider/token[.]actions[.]githubusercontent[.]com$", var.existing_github_oidc_provider_arn))
    )
    error_message = "existing_github_oidc_provider_arn must be the exact AWS GitHub Actions OIDC provider ARN."
  }
}

variable "database_username" {
  description = "Non-secret PostgreSQL application username injected through SSM for runtime consistency."
  type        = string
  default     = "crowdpass_app"

  validation {
    condition     = can(regex("^[a-z][a-z0-9_]{2,30}$", var.database_username))
    error_message = "database_username must be a lowercase PostgreSQL-compatible identifier."
  }
}

variable "database_password_generation" {
  description = "Shared intentional generation for the RDS master password and its authoritative SSM parameter. Increment only for deliberate rotation."
  type        = number
  default     = 1

  validation {
    condition     = var.database_password_generation >= 1 && floor(var.database_password_generation) == var.database_password_generation
    error_message = "database_password_generation must be a positive integer."
  }
}

variable "jwt_secret_generation" {
  description = "Intentional generation for the JWT signing secret."
  type        = number
  default     = 1

  validation {
    condition     = var.jwt_secret_generation >= 1 && floor(var.jwt_secret_generation) == var.jwt_secret_generation
    error_message = "jwt_secret_generation must be a positive integer."
  }
}

variable "rate_limit_secret_generation" {
  description = "Intentional generation for the rate-limit HMAC secret."
  type        = number
  default     = 1

  validation {
    condition     = var.rate_limit_secret_generation >= 1 && floor(var.rate_limit_secret_generation) == var.rate_limit_secret_generation
    error_message = "rate_limit_secret_generation must be a positive integer."
  }
}
