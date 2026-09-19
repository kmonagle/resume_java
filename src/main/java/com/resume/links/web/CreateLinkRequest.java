// Why this file exists: the JSON body of POST /links, exactly as the client sent it, before any
// validation. Every field is optional so a missing one becomes a validation message, not a crash.
package com.resume.links.web;

// JS/TS vs Java: Jackson (Spring's JSON library) fills a record from JSON by matching field names
// to the record's components. `maxClicks` is an Object on purpose: Jackson would quietly turn
// 1.5 into 1 or "5" into 5 if it were an Integer, whereas the contract wants a real whole number,
// so we receive whatever the JSON held (Integer, Double, String, Boolean...) and check its type
// ourselves in CreateLinkValidator.
public record CreateLinkRequest(
    String targetUrl, String title, String expiresAt, Object maxClicks, String shortCode) {}
