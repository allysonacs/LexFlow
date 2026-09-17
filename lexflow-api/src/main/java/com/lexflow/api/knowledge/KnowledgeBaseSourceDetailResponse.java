package com.lexflow.api.knowledge;

import com.lexflow.application.knowledge.KnowledgeBaseSourceDetail;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Uma fonte normativa e os seus trechos, na ordem do documento. */
public record KnowledgeBaseSourceDetailResponse(
        UUID id,
        String title,
        String sourceType,
        LocalDate effectiveDate,
        int chunkCount,
        List<KnowledgeBaseChunkResponse> chunks) {

    public static KnowledgeBaseSourceDetailResponse from(KnowledgeBaseSourceDetail detail) {
        return new KnowledgeBaseSourceDetailResponse(
                detail.source().id(),
                detail.source().title(),
                detail.source().sourceType().name(),
                detail.source().effectiveDate(),
                detail.chunks().size(),
                detail.chunks().stream().map(KnowledgeBaseChunkResponse::from).toList());
    }
}
