run "approved_temporary_demo_contract" {
  command = plan

  module {
    source = "./tests/contract"
  }

  assert {
    condition     = output.approved == true
    error_message = "The approved temporary-demo contract must plan successfully."
  }
}

run "reject_public_rds" {
  command = plan

  module {
    source = "./tests/contract"
  }

  variables {
    rds_publicly_accessible = true
  }

  expect_failures = [var.rds_publicly_accessible]
}

run "reject_multi_az_rds" {
  command = plan

  module {
    source = "./tests/contract"
  }

  variables {
    rds_multi_az = true
  }

  expect_failures = [var.rds_multi_az]
}

run "reject_wrong_architecture" {
  command = plan

  module {
    source = "./tests/contract"
  }

  variables {
    cpu_architecture = "X86_64"
  }

  expect_failures = [var.cpu_architecture]
}

run "reject_essential_redis" {
  command = plan

  module {
    source = "./tests/contract"
  }

  variables {
    redis_essential = true
  }

  expect_failures = [var.redis_essential]
}

run "reject_nonzero_bootstrap" {
  command = plan

  module {
    source = "./tests/contract"
  }

  variables {
    bootstrap_desired_count = 1
  }

  expect_failures = [var.bootstrap_desired_count]
}

run "reject_widened_oidc_subject" {
  command = plan

  module {
    source = "./tests/contract"
  }

  variables {
    github_oidc_subject = "repo:harshr2024@218149804/*:environment:*"
  }

  expect_failures = [var.github_oidc_subject]
}
