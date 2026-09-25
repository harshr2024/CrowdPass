resource "aws_db_subnet_group" "main" {
  name       = local.name_prefix
  subnet_ids = aws_subnet.database[*].id

  tags = {
    Name = local.name_prefix
  }
}

resource "aws_db_instance" "main" {
  identifier = local.name_prefix

  engine         = "postgres"
  engine_version = "17"
  instance_class = "db.t4g.micro"

  db_name  = local.database_name
  username = var.database_username
  port     = 5432

  password_wo         = ephemeral.aws_ssm_parameter.database_password.value
  password_wo_version = aws_ssm_parameter.database_password.version

  allocated_storage     = 20
  storage_type          = "gp3"
  storage_encrypted     = true
  max_allocated_storage = 0

  multi_az               = false
  publicly_accessible    = false
  availability_zone      = local.selected_availability_zones[0]
  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.database.id]

  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false
  apply_immediately           = true

  backup_retention_period  = 0
  delete_automated_backups = true
  deletion_protection      = false
  skip_final_snapshot      = true

  performance_insights_enabled = false
  monitoring_interval          = 0

  # Any managed change to the authoritative password parameter also replaces
  # this disposable database, preventing an independent parameter recreation
  # from silently leaving RDS with a different password.
  lifecycle {
    replace_triggered_by = [aws_ssm_parameter.database_password]
  }
}
