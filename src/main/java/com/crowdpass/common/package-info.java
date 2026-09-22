/**
 * Small, stable building blocks used by at least two domain modules (e.g. a shared time
 * source or pagination response type).
 *
 * <p>Rules: nothing here may depend on a domain module; nothing is added speculatively.
 * Code used by only one module belongs in that module.
 */
package com.crowdpass.common;
