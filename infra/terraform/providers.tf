provider "aws" {
  region = var.aws_region

  default_tags {
    tags = merge(local.required_tags, var.expires_at == null ? {} : {
      ExpiresAt = var.expires_at
    })
  }
}
