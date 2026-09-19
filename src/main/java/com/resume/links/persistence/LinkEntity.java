// Why this file exists: JPA's description of the `links` table. It is a MIRROR of the schema, not
// its source: the table is defined and migrated by Drizzle in the Next.js repo, and this service
// never creates or alters it. If the schema changes there, this class must follow.
package com.resume.links.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;

// JS/TS vs Java: a JPA ENTITY is a plain mutable class whose fields are the columns. JPA needs a
// no-argument constructor and non-final fields (it creates instances by reflection and changes
// them in place), which is why records can't be entities. Column names come from Spring Boot's
// naming strategy (`shortCode` becomes `short_code`), so only exceptions are spelled out.
// Hibernate is the JPA implementation underneath.
@Entity
@Table(name = "links")
public class LinkEntity {

  @Id private String id;

  private String shortCode;
  private String targetUrl;
  private String title;
  private String ownerId;

  // The database fills this in (DEFAULT now()); we only ever read it.
  @Column(insertable = false, updatable = false)
  private Instant createdAt;

  // JS/TS vs Java: @CurrentTimestamp makes HIBERNATE set this from the database's clock on every
  // INSERT and UPDATE: the counterpart of Drizzle's $onUpdate and SQLAlchemy's onupdate.
  @CurrentTimestamp(
      event = {EventType.INSERT, EventType.UPDATE},
      source = SourceType.DB)
  private Instant updatedAt;

  private Instant expiresAt;
  private Integer maxClicks;
  private int clickCount;
  private boolean isActive;

  protected LinkEntity() {
    // Required by JPA; protected so application code can't create a half-built entity.
  }

  // JS/TS vs Java: GETTERS AND SETTERS. Fields are private and read through methods, which is the
  // long-standing Java convention (and what JPA and many libraries expect). It is verbose: records
  // and Lombok exist to cut it down, but entities must be mutable classes, so it stays.
  public String getId() {
    return id;
  }

  public String getShortCode() {
    return shortCode;
  }

  public String getTargetUrl() {
    return targetUrl;
  }

  public String getTitle() {
    return title;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Integer getMaxClicks() {
    return maxClicks;
  }

  public int getClickCount() {
    return clickCount;
  }

  public boolean isActive() {
    return isActive;
  }

  public void setActive(boolean active) {
    this.isActive = active;
  }
}
