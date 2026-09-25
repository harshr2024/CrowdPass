resource "aws_iam_openid_connect_provider" "github" {
  count = var.existing_github_oidc_provider_arn == null ? 1 : 0

  url            = local.github_oidc_issuer
  client_id_list = [local.github_oidc_audience]

  tags = {
    Name = "${local.name_prefix}-github-actions"
  }
}

locals {
  github_oidc_provider_arn = coalesce(
    var.existing_github_oidc_provider_arn,
    try(aws_iam_openid_connect_provider.github[0].arn, null)
  )
}
