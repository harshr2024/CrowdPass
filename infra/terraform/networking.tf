data "aws_availability_zones" "available" {
  state = "available"

  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required"]
  }
}

locals {
  selected_availability_zones = slice(sort(data.aws_availability_zones.available.names), 0, 2)
}

resource "aws_vpc" "main" {
  cidr_block           = local.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = local.name_prefix
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = local.name_prefix
  }
}

resource "aws_subnet" "application" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = local.app_subnet_cidr
  availability_zone       = local.selected_availability_zones[0]
  map_public_ip_on_launch = false

  tags = {
    Name = "${local.name_prefix}-app-public-a"
  }
}

resource "aws_subnet" "database" {
  count = 2

  vpc_id                  = aws_vpc.main.id
  cidr_block              = local.database_subnet_cidrs[count.index]
  availability_zone       = local.selected_availability_zones[count.index]
  map_public_ip_on_launch = false

  tags = {
    Name = "${local.name_prefix}-db-private-${count.index == 0 ? "a" : "b"}"
  }
}

resource "aws_route_table" "application" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "${local.name_prefix}-public"
  }
}

resource "aws_route" "application_internet" {
  route_table_id         = aws_route_table.application.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route_table_association" "application" {
  subnet_id      = aws_subnet.application.id
  route_table_id = aws_route_table.application.id
}

check "two_distinct_availability_zones" {
  assert {
    condition     = length(distinct(local.selected_availability_zones)) == 2
    error_message = "The RDS subnet group requires two distinct available Availability Zones."
  }
}
