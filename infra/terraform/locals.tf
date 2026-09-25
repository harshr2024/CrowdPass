locals {
  name_prefix = "crowdpass-temporary-demo"

  required_tags = {
    Project     = "CrowdPass"
    ManagedBy   = "Terraform"
    Environment = "temporary-demo"
  }

  vpc_cidr              = "10.42.0.0/16"
  app_subnet_cidr       = "10.42.0.0/24"
  database_subnet_cidrs = ["10.42.10.0/24", "10.42.11.0/24"]
  database_name         = "crowdpass"
  ecr_repository_name   = "crowdpass-api"
  ecs_cluster_name      = local.name_prefix
  ecs_service_name      = local.name_prefix
  ecs_task_family       = local.name_prefix
  log_group_name        = "/crowdpass/temporary-demo"
  notification_queue    = "${local.name_prefix}-notifications"
  notification_dlq      = "${local.notification_queue}-dlq"
  github_oidc_issuer    = "https://token.actions.githubusercontent.com"
  github_oidc_audience  = "sts.amazonaws.com"
  github_oidc_subject   = "repo:harshr2024@218149804/CrowdPass@1386522027:environment:aws-demo"
  redis_image           = "redis@sha256:3811787313eba226a2ef38658c6ccb91cd5e110edc89c37767de373120a0e5a0"

  parameter_names = {
    database_username = "/crowdpass/temporary-demo/db/username"
    database_password = "/crowdpass/temporary-demo/db/password"
    jwt_secret        = "/crowdpass/temporary-demo/jwt-secret"
    rate_limit_secret = "/crowdpass/temporary-demo/rate-limit-secret"
  }
}
