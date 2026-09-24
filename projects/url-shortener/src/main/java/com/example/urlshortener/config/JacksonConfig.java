package com.example.urlshortener.config;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonFactory;

/**
 * Caps the length of any single JSON string while the request body is being parsed.
 *
 * <p>{@code @Size} on {@code CreateLinkRequest.url} only runs after Jackson has read the whole
 * value into memory. This limit makes the parser itself fail an oversized string instead. It is
 * twice that outer bound, so no request that {@code @Size} would accept can trip it.
 */
@Configuration(proxyBeanMethods = false)
public class JacksonConfig {

    static final int MAX_JSON_STRING_LENGTH = 16_384;

    /*
     * Applied to the mapper's own factory after it is built, via rebuild(), so Jackson's other
     * read constraints keep their defaults and no JsonFactory instance is shared between mappers.
     */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer streamReadConstraintsCustomizer() {
        return builder -> builder.postConfigurer(mapper -> {
            JsonFactory factory = mapper.getFactory();
            factory.setStreamReadConstraints(factory.streamReadConstraints()
                    .rebuild()
                    .maxStringLength(MAX_JSON_STRING_LENGTH)
                    .build());
        });
    }
}
