// Why this file exists: tests the parts of the HTTP layer that need no database (authentication,
// the owner header, the open /meta endpoint, the error shapes) by starting Spring's web layer with
// a
// mocked service.
package com.resume.links.web;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.resume.links.config.LinkProperties;
import com.resume.links.service.ClickLogger;
import com.resume.links.service.LinkService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// JS/TS vs Java: @WebMvcTest starts ONLY the web layer (controllers, the interceptor, the error
// handler, Jackson), not the database or the service: a "slice" test. It is supertest with the real
// routing and JSON handling inside. @MockitoBean replaces a bean with a Mockito mock (the closest
// thing to jest.mock here); the service is mocked because these tests are about HTTP, not rules.
// A slice does not load the settings class either, so it is switched on explicitly here, with a
// test token.
@WebMvcTest(LinkController.class)
@EnableConfigurationProperties(LinkProperties.class)
@TestPropertySource(properties = "link.backend.token=test-token-0123456789")
class LinkControllerTest {

  private static final String BEARER = "Bearer test-token-0123456789";

  @Autowired private MockMvc mvc;
  @MockitoBean private LinkService service;
  @MockitoBean private ClickLogger clickLogger;

  // JS/TS vs Java: `throws Exception` is a CHECKED-exception declaration: MockMvc's perform()
  // can throw, and the compiler insists it is either caught or declared. Tests just declare it.
  @Test
  void apiRoutesNeedTheBearerToken() throws Exception {
    mvc.perform(get("/links")).andExpect(status().isUnauthorized());
    mvc.perform(post("/links").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Missing or invalid bearer token"));
    mvc.perform(patch("/links/abc").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bearer nope", "Basic test-token-0123456789", "test-token-0123456789"})
  void aWrongSchemeOrTokenIsRejected(String header) throws Exception {
    mvc.perform(get("/links").header("Authorization", header).header("X-Owner-Id", "abc"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aTokenWithoutAUsableOwnerIsA400() throws Exception {
    mvc.perform(get("/links").header("Authorization", BEARER)).andExpect(status().isBadRequest());
    mvc.perform(get("/links").header("Authorization", BEARER).header("X-Owner-Id", "has space"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void authenticationIsCheckedBeforeTheBodyIsRead() throws Exception {
    // An unauthenticated caller must not learn whether its body was valid.
    mvc.perform(post("/links").contentType(MediaType.APPLICATION_JSON).content("{not json"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void validationErrorsUseTheContractsShape() throws Exception {
    mvc.perform(
            post("/links")
                .header("Authorization", BEARER)
                .header("X-Owner-Id", "owner-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetUrl\": \"javascript:alert(1)\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation failed"))
        .andExpect(jsonPath("$.fieldErrors.targetUrl[0]").value("Enter a valid http(s) URL"));
  }

  @Test
  void unparseableJsonIsA400NotA500() throws Exception {
    mvc.perform(
            post("/links")
                .header("Authorization", BEARER)
                .header("X-Owner-Id", "owner-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors.body").exists());
  }

  @Test
  void aNonBooleanIsActiveIsA400() throws Exception {
    mvc.perform(
            patch("/links/abc")
                .header("Authorization", BEARER)
                .header("X-Owner-Id", "owner-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isActive\": \"yes\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void metaIsOpenAndBrieflyCacheable() throws Exception {
    mvc.perform(get("/meta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.implementation").value("Java Spring Boot + JPA"))
        .andExpect(jsonPath("$.contractVersion").value("1"))
        .andExpect(header().string("Cache-Control", containsString("max-age=60")));
  }

  @Test
  void anUnknownShortCodeIsA404WithoutAToken() throws Exception {
    org.mockito.Mockito.when(service.follow("nope"))
        .thenReturn(new com.resume.links.service.FollowResult.NotFound());
    mvc.perform(get("/r/nope")).andExpect(status().isNotFound());
  }
}
