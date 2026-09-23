package com.example.urlshortener.admission;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Admits everything. The only implementation shipped in this iteration.
 *
 * <p>Registered from a {@code @Bean} method rather than component-scanned: {@code
 * @ConditionalOnMissingBean} on a scanned {@code @Component} evaluates after that class's own
 * definition is already registered, so the condition finds itself and the bean silently never
 * exists — the application then fails to start with "no qualifying bean of type
 * AdmissionControl" (found independently by three integration tests). A {@code @Bean} method's
 * condition is evaluated before its own definition is added, so this way a real limiter is still
 * added as one new {@code @Component} (annotated {@code @Primary} to take precedence), and
 * nothing here needs to change (ADR-005).
 */
@Configuration
public class AllowAllAdmissionControl {

    @Bean
    @ConditionalOnMissingBean(AdmissionControl.class)
    public AdmissionControl allowAllAdmissionControlBean() {
        // Admits unconditionally: rate limiting is out of scope for this iteration (ADR-005).
        return (caller, operation) -> { };
    }
}
