package com.lexflow.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.entity.AuditLogEntity;
import com.lexflow.infrastructure.persistence.entity.LegalCaseEntity;
import com.lexflow.infrastructure.persistence.repository.AuditLogJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * O que a migration {@code V9__audit_log_append_only.sql} garante no banco (Prompt 16, itens 3 e 5).
 *
 * <p>A proteção precisa ser do banco, e não do código: uma regra que só existe em Java protege
 * apenas o caminho que passa por Java. Por isso as tentativas abaixo são feitas pelos dois caminhos —
 * pelo repositório JPA e por SQL direto.
 */
class AuditLogPersistenceIT extends AbstractPersistenceIT {

    private static final Instant NOW = Instant.parse("2026-03-10T12:00:00Z");

    @Autowired
    private AuditLogJpaRepository auditLogRepository;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("uma linha de auditoria é gravada e recuperada pela demanda")
    void shouldPersistAndReadAuditLog() {
        UUID legalCaseId = givenLegalCase();

        AuditLogEntity saved = auditLogRepository.saveAndFlush(auditLog(legalCaseId, "STATUS_CHANGED"));
        entityManager.flush();
        entityManager.clear();

        assertThat(auditLogRepository.findByLegalCaseIdOrderByOccurredAtAscIdAsc(legalCaseId))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getId()).isEqualTo(saved.getId());
                    assertThat(entry.getEntityType()).isEqualTo("LEGAL_CASE");
                    assertThat(entry.getActor()).isEqualTo("SYSTEM");
                    assertThat(entry.getPayload()).contains("newStatus");
                });
    }

    @Test
    @DisplayName("critério de aceite: alterar, apagar ou esvaziar a trilha é recusado pelo banco")
    void shouldRejectEveryAttemptToRewriteTheTrail() throws Exception {
        // As tentativas rodam em uma conexão própria, com autocommit: no PostgreSQL, um erro aborta a
        // transação inteira, e dentro da transação do teste a primeira recusa impediria as demais de
        // serem sequer executadas.
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            insertAuditLog(connection, id);

            assertThatThrownBy(() -> execute(connection, "UPDATE audit_logs SET actor = 'outro' WHERE id = '%s'".formatted(id)))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> execute(connection, "DELETE FROM audit_logs WHERE id = '%s'".formatted(id)))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> execute(connection, "TRUNCATE audit_logs"))
                    .hasMessageContaining("append-only");

            // A linha continua lá, exatamente como foi gravada — inclusive depois deste teste, porque
            // nem o próprio teste consegue removê-la. É esse o ponto de uma trilha append-only.
            assertThat(actorOf(connection, id)).isEqualTo("SYSTEM");
        }
    }

    private static void insertAuditLog(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO audit_logs (id, entity_type, entity_id, legal_case_id, action, actor, payload, occurred_at)
                VALUES (?, 'LEGAL_CASE', ?, NULL, 'STATUS_CHANGED', 'SYSTEM', CAST(? AS jsonb), now())
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, UUID.randomUUID());
            statement.setString(3, "{\"newStatus\":\"EXTRACTING\"}");
            statement.executeUpdate();
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String actorOf(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT actor FROM audit_logs WHERE id = ?")) {
            statement.setObject(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private AuditLogEntity auditLog(UUID legalCaseId, String action) {
        return new AuditLogEntity(
                UUID.randomUUID(),
                "LEGAL_CASE",
                legalCaseId,
                legalCaseId,
                action,
                "SYSTEM",
                "{\"newStatus\":\"EXTRACTING\"}",
                NOW);
    }

    private UUID givenLegalCase() {
        return legalCaseRepository
                .saveAndFlush(new LegalCaseEntity(
                        UUID.randomUUID(),
                        null,
                        LegalCaseType.CONTRACT_SIGNING,
                        LegalCaseStatus.EXTRACTING,
                        "ana.silva",
                        null,
                        CasePriority.NORMAL,
                        NOW,
                        NOW))
                .getId();
    }
}
