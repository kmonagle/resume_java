// Why this file exists: what a successful click claim returns: the link's id (for the click log)
// and where to redirect.
package com.resume.links.service;

public record ClaimedLink(String linkId, String targetUrl) {}
