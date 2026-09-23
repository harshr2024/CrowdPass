package com.crowdpass.idempotency;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.crowdpass.user.AuthenticatedUserNotFoundException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Small explicit SQL boundary for ownership, replay snapshots, and bounded cleanup. */
@Component
public class IdempotencyRecordStore {

	private static final String FOREIGN_KEY_VIOLATION = "23503";

	private final JdbcTemplate jdbc;
	private final JsonMapper jsonMapper;

	public IdempotencyRecordStore(JdbcTemplate jdbc, JsonMapper jsonMapper) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
	}

	/** Returns the new row id only when this transaction acquired ownership. */
	public Optional<UUID> tryClaim(UUID userId, byte[] keyHash, byte[] fingerprint, Instant createdAt) {
		UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(null);
		try {
			List<UUID> inserted = jdbc.query("""
					INSERT INTO idempotency_records
					    (id, user_id, key_hash, request_fingerprint, state, created_at)
					VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?)
					ON CONFLICT (user_id, key_hash) DO NOTHING
					RETURNING id
					""", (rs, rowNum) -> rs.getObject("id", UUID.class), id, userId, keyHash, fingerprint, utc(createdAt));
			return inserted.stream().findFirst();
		}
		catch (DataIntegrityViolationException ex) {
			if (hasSqlState(ex, FOREIGN_KEY_VIOLATION)) {
				throw new AuthenticatedUserNotFoundException();
			}
			throw ex;
		}
	}

	public Optional<StoredRecord> find(UUID userId, byte[] keyHash) {
		List<StoredRecord> rows = jdbc.query("""
				SELECT request_fingerprint, state, response_status, response_body::text,
				       response_location, expires_at
				FROM idempotency_records
				WHERE user_id = ? AND key_hash = ?
				""", (rs, rowNum) -> new StoredRecord(
				rs.getBytes("request_fingerprint"),
				rs.getString("state"),
				(Integer) rs.getObject("response_status"),
				rs.getString("response_body") == null ? null : jsonMapper.readTree(rs.getString("response_body")),
				rs.getString("response_location"),
				rs.getObject("expires_at", OffsetDateTime.class)), userId, keyHash);
		return rows.stream().findFirst();
	}

	/** Must update exactly the IN_PROGRESS row owned by the current transaction. */
	public void complete(UUID id, int status, JsonNode body, String location, Instant completedAt, Instant expiresAt) {
		int updated = jdbc.update("""
				UPDATE idempotency_records
				SET state = 'COMPLETED', response_status = ?, response_body = CAST(? AS jsonb),
				    response_location = ?, completed_at = ?, expires_at = ?
				WHERE id = ? AND state = 'IN_PROGRESS'
				""", status, jsonMapper.writeValueAsString(body), location, utc(completedAt), utc(expiresAt), id);
		if (updated != 1) {
			throw new IllegalStateException("Idempotency ownership record could not be completed");
		}
	}

	/** Expiration is cleanup-only: no request path consults expires_at. */
	public int deleteExpiredCompleted(Instant now, int batchSize) {
		return jdbc.update("""
				WITH expired AS (
				    SELECT id FROM idempotency_records
				    WHERE state = 'COMPLETED' AND expires_at <= ?
				    ORDER BY expires_at, id
				    LIMIT ?
				    FOR UPDATE SKIP LOCKED
				)
				DELETE FROM idempotency_records record
				USING expired
				WHERE record.id = expired.id
				""", utc(now), batchSize);
	}

	private static boolean hasSqlState(Throwable failure, String expected) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql && expected.equals(sql.getSQLState())) {
				return true;
			}
		}
		return false;
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	public record StoredRecord(byte[] fingerprint, String state, Integer status, JsonNode body, String location,
			OffsetDateTime expiresAt) {
	}

}
