/**
 * PostgreSQL-backed HTTP idempotency for reservation creation. A claim, domain mutation, and
 * response snapshot share one transaction. This is separate from SQS consumer idempotency.
 */
package com.crowdpass.idempotency;
