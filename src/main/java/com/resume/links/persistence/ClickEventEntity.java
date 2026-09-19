// Why this file exists: JPA's description of the `click_events` table (see LinkEntity).
package com.resume.links.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "click_events")
public class ClickEventEntity {

  @Id private String id;

  private String linkId;

  // The database fills this in (DEFAULT now()).
  @Column(insertable = false, updatable = false)
  private Instant occurredAt;

  private String referrer;
  private String userAgent;

  protected ClickEventEntity() {}

  public ClickEventEntity(String linkId, String referrer, String userAgent) {
    this.id = UUID.randomUUID().toString();
    this.linkId = linkId;
    this.referrer = referrer;
    this.userAgent = userAgent;
  }
}
