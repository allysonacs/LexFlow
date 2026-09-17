package com.lexflow.infrastructure.extraction;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Monta o extrator de texto a partir de {@link TextExtractionProperties}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TextExtractionProperties.class)
public class TextExtractionConfiguration {

    @Bean
    public TikaDocumentTextExtractor tikaDocumentTextExtractor(TextExtractionProperties properties) {
        return new TikaDocumentTextExtractor(properties);
    }
}
