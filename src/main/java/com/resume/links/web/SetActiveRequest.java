// Why this file exists: the JSON body of PATCH /links/{id}.
package com.resume.links.web;

// `isActive` is an Object for the same reason as CreateLinkRequest.maxClicks: Jackson would coerce
// the string "true" into a boolean, but the contract accepts only a real JSON boolean.
public record SetActiveRequest(Object isActive) {}
