package com.lexflow.api.knowledge;

import java.time.LocalDate;

/**
 * Corpo JSON da indexação de uma fonte normativa enviada como texto.
 *
 * @param sourceType valor de {@code KnowledgeBaseSourceType} (ex.: {@code LAW}, {@code INTERNAL_POLICY})
 * @param effectiveDate data de vigência, no formato {@code AAAA-MM-DD}; opcional
 * @param text texto integral da norma
 */
public record KnowledgeBaseSourceRequest(String title, String sourceType, LocalDate effectiveDate, String text) {}
