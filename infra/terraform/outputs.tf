output "aws_region" {
  description = "Maps to the aws-demo AWS_REGION variable."
  value       = var.aws_region
}

output "github_deployment_role_arn" {
  description = "Maps to the aws-demo AWS_DEPLOY_ROLE_ARN variable."
  value       = aws_iam_role.github_deployment.arn
}

output "ecr_repository_name" {
  description = "Maps to the aws-demo ECR_REPOSITORY_NAME variable."
  value       = aws_ecr_repository.application.name
}

output "ecr_repository_uri" {
  description = "Maps to the aws-demo ECR_REPOSITORY_URI variable."
  value       = aws_ecr_repository.application.repository_url
}

output "ecs_cluster_name" {
  description = "Maps to the aws-demo ECS_CLUSTER_NAME variable."
  value       = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  description = "Maps to the aws-demo ECS_SERVICE_NAME variable."
  value       = aws_ecs_service.application.name
}

output "ecs_task_definition_family" {
  description = "Maps to the aws-demo ECS_TASK_DEFINITION_FAMILY variable."
  value       = aws_ecs_task_definition.bootstrap.family
}

output "ecs_task_execution_role_arn" {
  description = "Maps to the aws-demo ECS_TASK_EXECUTION_ROLE_ARN variable."
  value       = aws_iam_role.task_execution.arn
}

output "ecs_application_task_role_arn" {
  description = "Maps to the aws-demo ECS_APPLICATION_TASK_ROLE_ARN variable."
  value       = aws_iam_role.application_task.arn
}
