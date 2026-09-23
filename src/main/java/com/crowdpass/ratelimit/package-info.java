/**
 * Distributed fixed-window rate limiting backed by Redis. Used by the auth and reservation modules;
 * depends on neither. Redis holds only ephemeral counters: it is never a source of truth, and every
 * limit fails open if Redis is unavailable.
 */
package com.crowdpass.ratelimit;
