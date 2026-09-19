// Why this file exists: registers the auth interceptor on the /links routes. Routes NOT listed
// here (the redirect and /meta) are public by construction.
package com.resume.links.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final AuthInterceptor authInterceptor;

  public WebConfig(AuthInterceptor authInterceptor) {
    this.authInterceptor = authInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    // `/links/**` also matches `/links` itself.
    registry.addInterceptor(authInterceptor).addPathPatterns("/links/**");
  }
}
