terraform {
  required_version = "~> 1.16.0"
}

variable "rds_publicly_accessible" {
  type    = bool
  default = false

  validation {
    condition     = var.rds_publicly_accessible == false
    error_message = "The temporary database must remain private."
  }
}

variable "rds_multi_az" {
  type    = bool
  default = false

  validation {
    condition     = var.rds_multi_az == false
    error_message = "The temporary database must remain Single-AZ."
  }
}

variable "cpu_architecture" {
  type    = string
  default = "ARM64"

  validation {
    condition     = var.cpu_architecture == "ARM64"
    error_message = "The verified production task architecture is ARM64."
  }
}

variable "redis_essential" {
  type    = bool
  default = false

  validation {
    condition     = var.redis_essential == false
    error_message = "Redis must remain a nonessential ephemeral sidecar."
  }
}

variable "bootstrap_desired_count" {
  type    = number
  default = 0

  validation {
    condition     = var.bootstrap_desired_count == 0
    error_message = "Terraform must not start a bootstrap application task."
  }
}

variable "github_oidc_subject" {
  type    = string
  default = "repo:harshr2024@218149804/CrowdPass@1386522027:environment:aws-demo"

  validation {
    condition     = var.github_oidc_subject == "repo:harshr2024@218149804/CrowdPass@1386522027:environment:aws-demo"
    error_message = "GitHub OIDC trust must use the exact immutable repository and aws-demo environment subject."
  }
}

resource "terraform_data" "contract" {
  input = {
    rds_publicly_accessible = var.rds_publicly_accessible
    rds_multi_az            = var.rds_multi_az
    cpu_architecture        = var.cpu_architecture
    redis_essential         = var.redis_essential
    bootstrap_desired_count = var.bootstrap_desired_count
    github_oidc_subject     = var.github_oidc_subject
  }
}

output "approved" {
  value = (
    terraform_data.contract.input.rds_publicly_accessible == false &&
    terraform_data.contract.input.rds_multi_az == false &&
    terraform_data.contract.input.cpu_architecture == "ARM64" &&
    terraform_data.contract.input.redis_essential == false &&
    terraform_data.contract.input.bootstrap_desired_count == 0 &&
    terraform_data.contract.input.github_oidc_subject == "repo:harshr2024@218149804/CrowdPass@1386522027:environment:aws-demo"
  )
}
