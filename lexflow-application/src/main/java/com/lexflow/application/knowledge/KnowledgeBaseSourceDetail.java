package com.lexflow.application.knowledge;

import com.lexflow.domain.knowledge.KnowledgeBaseChunk;
import com.lexflow.domain.knowledge.KnowledgeBaseSource;
import java.util.List;
import java.util.Objects;

/** Uma fonte normativa e os seus trechos, na ordem do documento. */
public record KnowledgeBaseSourceDetail(KnowledgeBaseSource source, List<KnowledgeBaseChunk> chunks) {

    public KnowledgeBaseSourceDetail {
        Objects.requireNonNull(source, "source não pode ser nulo");
        chunks = List.copyOf(Objects.requireNonNull(chunks, "chunks não pode ser nulo"));
    }
}
