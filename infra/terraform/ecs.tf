resource "aws_ecs_cluster" "main" {
  name = local.ecs_cluster_name

  configuration {
    execute_command_configuration {
      logging = "DEFAULT"
    }
  }
}

resource "aws_ecs_task_definition" "bootstrap" {
  family                   = local.ecs_task_family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = "512"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.task_execution.arn
  task_role_arn            = aws_iam_role.application_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([
    {
      name      = "crowdpass-api"
      image     = "${aws_ecr_repository.application.repository_url}:bootstrap-not-deployable"
      essential = true
      cpu       = 384
      memory    = 1792
      user      = "10001:10001"

      readonlyRootFilesystem = false
      privileged             = false

      linuxParameters = {
        capabilities = {
          drop = ["ALL"]
        }
        initProcessEnabled = true
      }

      environment = [
        { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/${local.database_name}" },
        { name = "REDIS_HOST", value = "127.0.0.1" },
        { name = "REDIS_PORT", value = "6379" },
        { name = "CROWDPASS_SQS_REGION", value = var.aws_region },
        { name = "CROWDPASS_SQS_QUEUE", value = aws_sqs_queue.notification.name },
        { name = "CROWDPASS_MESSAGING_PUBLISHER_ENABLED", value = "true" },
        { name = "CROWDPASS_MESSAGING_CONSUMER_ENABLED", value = "true" }
      ]

      secrets = [
        { name = "DB_USERNAME", valueFrom = aws_ssm_parameter.database_username.arn },
        { name = "DB_PASSWORD", valueFrom = aws_ssm_parameter.database_password.arn },
        { name = "CROWDPASS_JWT_SECRET", valueFrom = aws_ssm_parameter.jwt_secret.arn },
        { name = "CROWDPASS_RATE_LIMIT_SECRET", valueFrom = aws_ssm_parameter.rate_limit_secret.arn }
      ]

      healthCheck = {
        command     = ["CMD", "curl", "--fail", "--silent", "--show-error", "--max-time", "2", "http://127.0.0.1:8080/readyz"]
        interval    = 10
        timeout     = 3
        retries     = 5
        startPeriod = 30
      }

      stopTimeout = 40

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.application.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "app"
        }
      }
    },
    {
      name      = "redis"
      image     = local.redis_image
      essential = false
      cpu       = 128
      memory    = 256
      user      = "999:1000"

      readonlyRootFilesystem = false
      privileged             = false
      command                = ["redis-server", "--save", "", "--appendonly", "no", "--dir", "/tmp"]

      linuxParameters = {
        capabilities = {
          drop = ["ALL"]
        }
        initProcessEnabled = true
      }

      healthCheck = {
        command     = ["CMD", "redis-cli", "ping"]
        interval    = 10
        timeout     = 3
        retries     = 3
        startPeriod = 5
      }

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.application.name
          awslogs-region        = var.aws_region
          awslogs-stream-prefix = "redis"
        }
      }
    }
  ])
}

resource "aws_ecs_service" "application" {
  name            = local.ecs_service_name
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.bootstrap.arn
  desired_count   = 0

  launch_type            = "FARGATE"
  platform_version       = "1.4.0"
  enable_execute_command = true

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = [aws_subnet.application.id]
    security_groups  = [aws_security_group.task.id]
    assign_public_ip = true
  }

  # Terraform owns the zero-count bootstrap shape. After the first deployment,
  # CD owns the active task revision and desired count. No other service fields
  # are ignored.
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
}
