package com.lexflow.application.legalcase;

import com.lexflow.application.analysis.LegalCaseAnalysisResult;
import com.lexflow.application.fact.FactExtractionResult;
import com.lexflow.domain.checklist.DocumentChecklist;
import com.lexflow.domain.classification.LegalCaseClassification;
import com.lexflow.domain.document.DocumentTextContent;
import com.lexflow.domain.document.TextExtractionStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resultado do processamento de um evento, com o que o consumidor precisa para registrar o desfecho.
 *
 * @param classification classificação feita nesta entrega; nula quando a entrega foi descartada ou
 *     quando a demanda já tinha sido classificada em uma tentativa anterior
 * @param checklist checklist documental após a sincronização; nulo quando a entrega foi descartada
 * @param textContents texto de cada documento da demanda; vazio quando a entrega foi descartada
 * @param factExtraction resultado da extração de fatos; nulo quando a entrega foi descartada ou a
 *     etapa está desligada
 * @param analysis resultado da análise jurídica; nulo quando a entrega foi descartada, a etapa está
 *     desligada ou a demanda não chegou a {@code AI_ANALYSIS_IN_PROGRESS}
 */
public record LegalCaseProcessingResult(
        LegalCaseProcessingOutcome outcome,
        LegalCaseClassification classification,
        DocumentChecklist checklist,
        List<DocumentTextContent> textContents,
        FactExtractionResult factExtraction,
        LegalCaseAnalysisResult analysis) {

    public LegalCaseProcessingResult {
        Objects.requireNonNull(outcome, "outcome não pode ser nulo");
        textContents = List.copyOf(Objects.requireNonNull(textContents, "textContents não pode ser nulo"));
    }

    /** Resultado de uma entrega descartada, sem nenhum trabalho feito. */
    public static LegalCaseProcessingResult skipped(LegalCaseProcessingOutcome outcome) {
        if (!outcome.isSkipped()) {
            throw new IllegalArgumentException("desfecho não é de descarte: " + outcome);
        }
        return new LegalCaseProcessingResult(outcome, null, null, List.of(), null, null);
    }

    public Optional<LegalCaseClassification> classificationIfPerformed() {
        return Optional.ofNullable(classification);
    }

    public Optional<DocumentChecklist> checklistIfEvaluated() {
        return Optional.ofNullable(checklist);
    }

    public Optional<FactExtractionResult> factExtractionIfPerformed() {
        return Optional.ofNullable(factExtraction);
    }

    public Optional<LegalCaseAnalysisResult> analysisIfPerformed() {
        return Optional.ofNullable(analysis);
    }

    /** Quantidade de documentos com o status de extração informado. */
    public long countByStatus(TextExtractionStatus status) {
        return textContents.stream().filter(content -> content.status() == status).count();
    }

    /** Indica se a entrega foi descartada sem que nada tenha sido feito. */
    public boolean isSkipped() {
        return outcome.isSkipped();
    }
}
