resource "aws_security_group" "task" {
  name        = "${local.name_prefix}-task"
  description = "No-ingress ECS task security group"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-task"
  }
}

resource "aws_security_group" "database" {
  name        = "${local.name_prefix}-database"
  description = "Private PostgreSQL access only from the CrowdPass task"
  vpc_id      = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-database"
  }
}

resource "aws_vpc_security_group_egress_rule" "task_https" {
  security_group_id = aws_security_group.task.id
  description       = "AWS public APIs, ECR image pulls, and external HTTPS"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "task_postgresql" {
  security_group_id            = aws_security_group.task.id
  description                  = "PostgreSQL to the private database only"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.database.id
}

resource "aws_vpc_security_group_egress_rule" "task_dns_udp" {
  security_group_id = aws_security_group.task.id
  description       = "UDP DNS to the VPC resolver"
  ip_protocol       = "udp"
  from_port         = 53
  to_port           = 53
  cidr_ipv4         = "${cidrhost(local.vpc_cidr, 2)}/32"
}

resource "aws_vpc_security_group_egress_rule" "task_dns_tcp" {
  security_group_id = aws_security_group.task.id
  description       = "TCP DNS to the VPC resolver"
  ip_protocol       = "tcp"
  from_port         = 53
  to_port           = 53
  cidr_ipv4         = "${cidrhost(local.vpc_cidr, 2)}/32"
}

resource "aws_vpc_security_group_ingress_rule" "database_postgresql" {
  security_group_id            = aws_security_group.database.id
  description                  = "PostgreSQL from the CrowdPass task only"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.task.id
}
