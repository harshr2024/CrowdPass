data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  ecs_task_trust_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "AllowEcsTasksInThisAccount"
      Effect = "Allow"
      Principal = {
        Service = "ecs-tasks.amazonaws.com"
      }
      Action = "sts:AssumeRole"
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = data.aws_caller_identity.current.account_id
        }
        ArnLike = {
          "aws:SourceArn" = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:*"
        }
      }
    }]
  })
}

resource "aws_iam_role" "task_execution" {
  name               = "${local.name_prefix}-execution"
  assume_role_policy = local.ecs_task_trust_policy
}

resource "aws_iam_role_policy" "task_execution" {
  name = "${local.name_prefix}-execution"
  role = aws_iam_role.task_execution.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuthorizationTokenRequiresWildcard"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "PullOnlyCrowdPassRepository"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer"
        ]
        Resource = aws_ecr_repository.application.arn
      },
      {
        Sid    = "WriteOnlyCrowdPassLogGroup"
        Effect = "Allow"
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Resource = "${aws_cloudwatch_log_group.application.arn}:*"
      },
      {
        Sid    = "InjectOnlyCrowdPassParameters"
        Effect = "Allow"
        Action = "ssm:GetParameters"
        Resource = [
          aws_ssm_parameter.database_username.arn,
          aws_ssm_parameter.database_password.arn,
          aws_ssm_parameter.jwt_secret.arn,
          aws_ssm_parameter.rate_limit_secret.arn
        ]
      }
    ]
  })
}

resource "aws_iam_role" "application_task" {
  name               = "${local.name_prefix}-task"
  assume_role_policy = local.ecs_task_trust_policy
}

resource "aws_iam_role_policy" "application_task" {
  name = "${local.name_prefix}-task"
  role = aws_iam_role.application_task.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "UseOnlyNotificationQueue"
        Effect = "Allow"
        Action = [
          "sqs:DeleteMessage",
          "sqs:GetQueueUrl",
          "sqs:ReceiveMessage",
          "sqs:SendMessage"
        ]
        Resource = aws_sqs_queue.notification.arn
      },
      {
        # The ECS Exec ssmmessages channel APIs do not support resource-level IAM constraints.
        Sid    = "EcsExecChannelsRequireWildcard"
        Effect = "Allow"
        Action = [
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel"
        ]
        Resource = "*"
      }
    ]
  })
}

resource "aws_iam_role" "github_deployment" {
  name                 = "${local.name_prefix}-github-deploy"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "ExactCrowdPassAwsDemoEnvironment"
      Effect = "Allow"
      Principal = {
        Federated = local.github_oidc_provider_arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = local.github_oidc_audience
          "token.actions.githubusercontent.com:sub" = local.github_oidc_subject
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "github_deployment" {
  name = "${local.name_prefix}-github-deploy"
  role = aws_iam_role.github_deployment.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuthorizationTokenRequiresWildcard"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "PushAndInspectOnlyCrowdPassRepository"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:BatchGetImage",
          "ecr:CompleteLayerUpload",
          "ecr:DescribeImages",
          "ecr:DescribeRepositories",
          "ecr:GetDownloadUrlForLayer",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart"
        ]
        Resource = aws_ecr_repository.application.arn
      },
      {
        Sid    = "InspectAndUpdateOnlyCrowdPassService"
        Effect = "Allow"
        Action = [
          "ecs:UpdateService"
        ]
        Resource = aws_ecs_service.application.id
      },
      {
        # ECS Describe APIs do not support resource-level IAM permissions.
        Sid    = "EcsDescribeApisRequireWildcard"
        Effect = "Allow"
        Action = [
          "ecs:DescribeClusters",
          "ecs:DescribeServices",
          "ecs:DescribeTaskDefinition",
          "ecs:DescribeTasks"
        ]
        Resource = "*"
      },
      {
        # ECS ListTasks requires a wildcard resource; the cluster condition narrows the request.
        Sid      = "EcsListTasksRequiresWildcard"
        Effect   = "Allow"
        Action   = "ecs:ListTasks"
        Resource = "*"
        Condition = {
          ArnEquals = {
            "ecs:cluster" = aws_ecs_cluster.main.arn
          }
        }
      },
      {
        # ECS RegisterTaskDefinition does not support resource-level permissions.
        Sid      = "EcsRegisterTaskDefinitionRequiresWildcard"
        Effect   = "Allow"
        Action   = "ecs:RegisterTaskDefinition"
        Resource = "*"
      },
      {
        Sid      = "InspectOnlyCrowdPassTaskDefinitionTags"
        Effect   = "Allow"
        Action   = "ecs:ListTagsForResource"
        Resource = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task-definition/${local.ecs_task_family}:*"
      },
      {
        Sid      = "TagOnlyNewCrowdPassTaskDefinitionRevisions"
        Effect   = "Allow"
        Action   = "ecs:TagResource"
        Resource = "arn:${data.aws_partition.current.partition}:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task-definition/${local.ecs_task_family}:*"
        Condition = {
          StringEquals = {
            "ecs:CreateAction" = "RegisterTaskDefinition"
          }
        }
      },
      {
        Sid      = "PassOnlyCrowdPassTaskRolesToEcs"
        Effect   = "Allow"
        Action   = "iam:PassRole"
        Resource = [aws_iam_role.task_execution.arn, aws_iam_role.application_task.arn]
        Condition = {
          StringEquals = {
            "iam:PassedToService" = "ecs-tasks.amazonaws.com"
          }
        }
      }
    ]
  })
}
