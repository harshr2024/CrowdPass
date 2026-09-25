# Phase 10C ordered teardown

For Phase 12, `terraform destroy` is the authoritative teardown mechanism for managed resources.
This historical checklist remains the post-destroy survivor audit and emergency fallback. Never
manually delete an ambiguous resource merely to make Terraform succeed; reconcile it with state and
the exact CrowdPass names/tags first.

This runbook is prepared before provisioning. Run it the same day immediately after evidence
capture. Record each completed item and do not treat a stopped RDS instance or desired-count-zero
service as deleted.

1. **Freeze the evidence set.** Close ECS Exec/SSE sessions and remove any temporary local files
   containing rendered account IDs, endpoints, task ARNs, tokens, or generated secrets.
2. **Stop compute first.** Set ECS desired count to zero, wait for the Fargate task to stop, and
   confirm its AWS-provided public IPv4/ENI is released. This ends Fargate and IPv4 hourly charges.
3. **Delete ECS resources.** Delete the ECS service, deregister temporary task-definition revisions,
   and delete the cluster after managed agents/tasks are gone.
4. **Delete RDS, not merely stop it.** Delete the DB with final snapshot skipped and retained
   automated backups disabled. Wait for deletion, then inspect automated backups and manual
   snapshots. Delete a survivor only when its source DB identifier, creation record, and tags prove
   it belongs to this exact demo; stop if ownership is ambiguous. Storage/snapshots can bill after
   compute stops.
5. **Delete database networking metadata.** Delete the DB subnet group after RDS finishes deleting.
6. **Delete messaging.** Delete the main Standard queue and its DLQ after the final evidence is
   captured.
7. **Delete images.** Delete the disposable ECR image/tag and repository; confirm no untagged image
   manifests remain.
8. **Delete logs.** Export only the required sanitized evidence, then delete
   `/crowdpass/temporary-demo`. If evidence export temporarily delays deletion, verify the approved
   seven-day retention and keep teardown marked incomplete until the log group is deleted. Never
   leave retention unlimited.
9. **Delete parameters.** Delete all four `/crowdpass/temporary-demo/...` SSM parameters. Their
   values must not be exported into teardown records.
10. **Delete IAM artifacts.** Detach/delete the temporary application and execution policies and
    roles. Remove any temporary operator permission that existed only for Phase 10C/ECS Exec.
11. **Delete network resources.** After RDS and ECS ENIs disappear, delete the DB/task security
    groups, private and public subnets, route-table associations and custom route table, detach and
    delete the Internet Gateway, and delete the VPC.
12. **Check excluded resources anyway.** Confirm there is no NAT Gateway/EIP, ALB/listener/target
    group, ElastiCache cluster/serverless cache/backup, VPC endpoint, Route 53 resource, EFS file
    system, Cloud Map namespace, Secrets Manager secret, or customer-managed KMS key bearing the
    CrowdPass tags/name.
13. **Final tagged inventory.** Search every regional service used above for `Project=CrowdPass` and
    the name prefix `crowdpass-temporary-demo`. Investigate every survivor rather than assuming it
    is free.
14. **Billing follow-up.** Record teardown UTC time. Keep the `$5` Budget notifications until Cost
    Explorer/billing data catches up and verify the approximate actual charge/credit offset. If the
    Budget was created specifically for this demo, delete it after that check. If it predated the
    demo, leave it untouched.

The only intended survivors are sanitized local evidence, repository documentation, and any Budget
that explicitly predated the demo. No final DB snapshot, retained automated backup, ECR image, log
group, secret parameter, public IPv4, or other billable AWS resource should remain.
