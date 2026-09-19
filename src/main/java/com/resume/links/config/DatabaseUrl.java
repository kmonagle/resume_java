// Why this file exists: Neon and Render hand out a database URL
// (postgres://user:pw@host/db?sslmode=require), but the PostgreSQL JDBC driver wants
// `jdbc:postgresql://host/db` with the username and password supplied separately. This converts
// one to the other and applies the settings that matter for a shared, PgBouncer-fronted database.
//
// JS/TS vs Java: this is the counterpart of src/server/db/client.ts in the Next.js repo. The
// postgres.js driver accepts a URL directly; JDBC does not, so we do it here.
package com.resume.links.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public record DatabaseUrl(String jdbcUrl, String username, String password) {

  public static DatabaseUrl parse(String url) {
    // Already a JDBC URL (for example "jdbc:postgresql://..."): use as is.
    if (url.startsWith("jdbc:")) {
      return new DatabaseUrl(url, null, null);
    }

    URI uri = URI.create(url);
    // JS/TS vs Java: `String[]` is a fixed-size ARRAY (its length can't change; use List for
    // growable). `split(":", 2)` limits the split to two parts, so a password containing ':'
    // survives.
    String[] credentials =
        uri.getUserInfo() == null ? new String[0] : uri.getUserInfo().split(":", 2);
    int port = uri.getPort() > 0 ? uri.getPort() : 5432;

    List<String> params = new ArrayList<>();
    // libpq's `sslmode` is understood by the JDBC driver under the same name, so it is passed
    // through. `channel_binding` (which Neon's copy button adds) is dropped: it is not a JDBC
    // setting.
    if (uri.getQuery() != null) {
      for (String pair : uri.getQuery().split("&")) {
        if (pair.startsWith("sslmode=")) {
          params.add(pair);
        }
      }
    }
    // Neon's pooled URL goes through PgBouncer in transaction mode, which cannot keep the
    // server-side prepared statements the JDBC driver creates after a few executions. Threshold 0
    // turns that off (the Java twin of the Next.js app's `prepare: false`).
    params.add("prepareThreshold=0");

    String jdbc =
        "jdbc:postgresql://"
            + uri.getHost()
            + ":"
            + port
            + uri.getPath()
            + "?"
            + String.join("&", params);
    return new DatabaseUrl(
        jdbc,
        credentials.length > 0 ? decode(credentials[0]) : null,
        credentials.length > 1 ? decode(credentials[1]) : null);
  }

  // URL credentials are percent-encoded; JDBC's are not.
  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
