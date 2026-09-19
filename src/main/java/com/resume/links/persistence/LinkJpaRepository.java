// Why this file exists: Spring Data JPA's repository for links. Spring generates the
// implementation of this interface at startup from the method names and annotations.
package com.resume.links.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// JS/TS vs Java: you write only the INTERFACE. Method names like
// `findByOwnerIdOrderByCreatedAtDesc`
// are parsed into queries ("derived queries": find where ownerId = ?, order by createdAt desc), and
// @Query supplies SQL for the rest. Spring builds a proxy class that implements it, so there is no
// data-access code to write.
public interface LinkJpaRepository extends JpaRepository<LinkEntity, String> {

  Optional<LinkEntity> findByShortCode(String shortCode);

  List<LinkEntity> findByOwnerIdOrderByCreatedAtDesc(String ownerId);

  Optional<LinkEntity> findByIdAndOwnerId(String id, String ownerId);

  long countByOwnerId(String ownerId);

  // JPQL bulk delete (JPQL is JPA's SQL-like query language over entities). click_events rows go
  // with their link via the foreign key's ON DELETE CASCADE, which the database enforces.
  @Modifying
  @Query("delete from LinkEntity l where l.createdAt < :cutoff")
  int deleteCreatedBefore(@Param("cutoff") Instant cutoff);

  // Insert unless the short code is taken, returning the number of rows inserted (1 or 0).
  //
  // ON CONFLICT DO NOTHING lets Postgres arbitrate a race between two requests for the same code,
  // and it must be done this way in Spring: a plain save() that hits the unique constraint throws
  // AND leaves the surrounding transaction ABORTED (Postgres rejects every further statement in
  // it), so a "catch and retry with a fresh code" would fail. ON CONFLICT never raises.
  // Also: save() on an entity with an assigned id does a needless SELECT first (merge).
  @Modifying
  @Query(
      // JS/TS vs Java: `"""` opens a TEXT BLOCK: a multi-line string literal (like a JS template
      // literal without the interpolation). The common leading indentation is stripped, so the SQL
      // can be laid out naturally. Values reach the query as :named parameters, never by string
      // concatenation, so SQL injection through them is impossible.
      value =
          """
          INSERT INTO links (id, short_code, target_url, title, owner_id, expires_at, max_clicks)
          VALUES (:id, :shortCode, :targetUrl, :title, :ownerId, :expiresAt, :maxClicks)
          ON CONFLICT (short_code) DO NOTHING
          """,
      nativeQuery = true)
  int insertIfAbsent(
      @Param("id") String id,
      @Param("shortCode") String shortCode,
      @Param("targetUrl") String targetUrl,
      @Param("title") String title,
      @Param("ownerId") String ownerId,
      @Param("expiresAt") Instant expiresAt,
      @Param("maxClicks") Integer maxClicks);

  // Checks every redeemability rule AND counts the click in ONE statement.
  //
  // Loading the entity, checking maxClicks in Java, then saving would let two concurrent requests
  // both read "9 of 10", both pass, and both redirect (ending at 11). In a single UPDATE,
  // Postgres locks the row: the second request waits, then re-evaluates the WHERE clause against
  // the already-incremented row (10) and matches nothing. The database enforces the limit however
  // many instances or languages are calling it. Must stay identical to the other implementations.
  //
  // Returns the number of rows updated: 1 = claimed, 0 = missing or not redeemable. (JPA can't
  // return columns from an UPDATE, so the target URL is read with a separate query afterwards;
  // that is safe: the decision was already made atomically.)
  @Modifying
  @Query(
      value =
          """
          UPDATE links SET click_count = click_count + 1, updated_at = now()
          WHERE short_code = :code
            AND is_active
            AND (expires_at IS NULL OR expires_at > now())
            AND (max_clicks IS NULL OR click_count < max_clicks)
          """,
      nativeQuery = true)
  int claim(@Param("code") String code);
}
