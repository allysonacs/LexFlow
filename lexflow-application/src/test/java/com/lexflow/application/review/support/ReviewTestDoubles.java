package com.lexflow.application.review.support;

import com.lexflow.application.review.DecisionRegisteredEvent;
import com.lexflow.application.review.DecisionRegisteredEventPublisher;
import com.lexflow.application.review.DecisionRepository;
import com.lexflow.domain.decision.Decision;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Dublês das portas da revisão humana. */
public final class ReviewTestDoubles {

    private ReviewTestDoubles() {
        // classe utilitária
    }

    /** Decisões em memória. */
    public static final class InMemoryDecisionRepository implements DecisionRepository {

        private final Map<UUID, Decision> decisions = new LinkedHashMap<>();

        @Override
        public Decision save(Decision decision) {
            decisions.put(decision.id(), decision);
            return decision;
        }

        @Override
        public Optional<Decision> findById(UUID id) {
            return Optional.ofNullable(decisions.get(id));
        }

        @Override
        public List<Decision> findByLegalCaseId(UUID legalCaseId) {
            return decisions.values().stream()
                    .filter(decision -> decision.legalCaseId().equals(legalCaseId))
                    .sorted(Comparator.comparing(Decision::decidedAt))
                    .toList();
        }

        public int size() {
            return decisions.size();
        }
    }

    /** Publicador que guarda os eventos, para o teste conferir o que seria enviado à fila. */
    public static final class RecordingDecisionEventPublisher implements DecisionRegisteredEventPublisher {

        private final List<DecisionRegisteredEvent> events = new ArrayList<>();

        @Override
        public void publish(DecisionRegisteredEvent event) {
            events.add(event);
        }

        public List<DecisionRegisteredEvent> events() {
            return List.copyOf(events);
        }
    }
}
