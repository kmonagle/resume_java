// Why this file exists: small infrastructure beans that don't belong anywhere else: the clock, and
// switching on @Async support.
package com.resume.links.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

@Configuration
@EnableAsync
public class AppConfig {

  // The real clock, in UTC. Tests supply a fixed one instead (see LinkServiceTest).
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }
}
