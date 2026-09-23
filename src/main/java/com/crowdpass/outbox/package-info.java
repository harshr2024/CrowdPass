/**
 * Transactional outbox. Domain modules append events inside their own transaction; the publisher
 * sends pending events to SQS. Guarantees: a committed domain change has a durable event and a
 * rolled-back change has none; publication is at-least-once, so consumers must be idempotent.
 * This module has no knowledge of any domain module.
 */
package com.crowdpass.outbox;
