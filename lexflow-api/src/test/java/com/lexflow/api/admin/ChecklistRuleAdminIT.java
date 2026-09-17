package com.lexflow.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.domain.checklist.ChecklistItemStatus;
import com.lexflow.domain.legalcase.CasePriority;
import com.lexflow.domain.legalcase.LegalCaseStatus;
import com.lexflow.domain.legalcase.LegalCaseType;
import com.lexflow.infrastructure.persistence.entity.DocumentChecklistItemEntity;
import com.lexflow.infrastructure.persistence.entity.LegalCaseEntity;
import com.lexflow.infrastructure.persistence.repository.ChecklistRuleJpaRepository;
import com.lexflow.infrastructure.persistence.repository.DocumentChecklistItemJpaRepository;
import com.lexflow.infrastructure.persistence.repository.LegalCaseJpaRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * API administrativa das regras de checklist (Prompt 09), com autenticação real.
 *
 * <p>As regras criadas aqui usam códigos próprios ({@code IT_...}) e são removidas ao fim de cada
 * teste: o banco é compartilhado com os demais testes da API, e uma regra esquecida mudaria o
 * checklist que eles verificam.
 *
 * <p>Requer Docker em execução.
 */
class ChecklistRuleAdminIT extends AbstractApiIT {

    private static final String BASE_PATH = ChecklistRuleAdminController.BASE_PATH;

    /** Credenciais do perfil dev, usadas pelos testes. */
    private static final String ADMIN_USER = "admin";

    private static final String ADMIN_PASSWORD = "lexflow-admin";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ChecklistRuleJpaRepository ruleRepository;

    @Autowired
    private DocumentChecklistItemJpaRepository itemRepository;

    @Autowired
    private LegalCaseJpaRepository legalCaseRepository;

    private final List<UUID> createdRules = new ArrayList<>();

    @AfterEach
    void removeCreatedRules() {
        for (UUID ruleId : createdRules) {
            itemRepository.findAll().stream()
                    .filter(item -> ruleId.equals(item.getChecklistRuleId()))
                    .forEach(itemRepository::delete);
            ruleRepository.deleteById(ruleId);
        }
        createdRules.clear();
    }

    private TestRestTemplate admin() {
        return restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASSWORD);
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private static String idOf(ResponseEntity<String> response) {
        return response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private ResponseEntity<String> createRule(String caseType, String documentType, boolean mandatory) {
        ResponseEntity<String> response = admin().postForEntity(
                BASE_PATH,
                json("""
                        {"caseType":"%s","requiredDocumentType":"%s","description":"Regra de teste","mandatory":%s}
                        """.formatted(caseType, documentType, mandatory)),
                String.class);
        if (response.getStatusCode() == HttpStatus.CREATED) {
            createdRules.add(UUID.fromString(idOf(response)));
        }
        return response;
    }

    @Test
    @DisplayName("sem credenciais, a API administrativa responde 401 no formato padrão de erro")
    void shouldRequireAuthentication() {
        ResponseEntity<String> response = restTemplate.getForEntity(BASE_PATH, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Basic");
        assertThat(response.getBody())
                .contains("\"code\":\"UNAUTHORIZED\"")
                .contains("\"path\":\"%s\"".formatted(BASE_PATH));
    }

    @Test
    @DisplayName("senha errada também responde 401, e nada é criado")
    void shouldRejectWrongPassword() {
        long before = ruleRepository.count();

        ResponseEntity<String> response = restTemplate
                .withBasicAuth(ADMIN_USER, "senha-errada")
                .postForEntity(BASE_PATH, json("""
                        {"caseType":"PROPOSAL_ACCEPTANCE","requiredDocumentType":"IT_X","mandatory":true}
                        """), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(ruleRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("as rotas fora de /api/v1/admin continuam abertas")
    void shouldKeepOtherRoutesOpen() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/api/v1/legal-cases/" + UUID.randomUUID(), String.class)
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("o administrador cria, consulta, altera e exclui uma regra")
    void shouldManageRuleLifecycle() {
        ResponseEntity<String> created = createRule("PROPOSAL_ACCEPTANCE", "it_board_approval", false);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = idOf(created);
        assertThat(created.getHeaders().getLocation()).hasToString(BASE_PATH + "/" + id);
        assertThat(created.getBody())
                .contains("\"requiredDocumentType\":\"IT_BOARD_APPROVAL\"")
                .contains("\"caseType\":\"PROPOSAL_ACCEPTANCE\"")
                .contains("\"mandatory\":false");

        assertThat(admin().getForEntity(BASE_PATH + "/" + id, String.class).getBody())
                .contains("Regra de teste");

        ResponseEntity<String> updated = admin().exchange(
                BASE_PATH + "/" + id,
                HttpMethod.PUT,
                json("""
                        {"requiredDocumentType":"IT_BOARD_MINUTES","description":"Ata do conselho","mandatory":false}
                        """),
                String.class);
        assertThat(updated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updated.getBody())
                .contains("\"requiredDocumentType\":\"IT_BOARD_MINUTES\"")
                .contains("Ata do conselho");

        ResponseEntity<Void> deleted = admin().exchange(BASE_PATH + "/" + id, HttpMethod.DELETE, null, Void.class);
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(admin().getForEntity(BASE_PATH + "/" + id, String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        createdRules.remove(UUID.fromString(id));
    }

    @Test
    @DisplayName("a listagem é paginada e aceita filtro por tipo, incluindo as regras do seed")
    void shouldListRules() {
        ResponseEntity<String> page = admin().getForEntity(BASE_PATH + "?caseType=CONTRACT_SIGNING&size=2", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody())
                .contains("\"page\":0")
                .contains("\"size\":2")
                .contains("\"totalElements\":3")
                .contains("\"totalPages\":2")
                .contains("CONTRACT_DRAFT")
                .contains("FINANCIAL_OPINION")
                .doesNotContain("SIGNATORY_POWERS")
                .doesNotContain("SUPPLIER_CNPJ_CARD");
        assertThat(admin().getForEntity(BASE_PATH + "?size=500", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("regra duplicada responde 409")
    void shouldRejectDuplicate() {
        ResponseEntity<String> response = createRule("CONTRACT_SIGNING", "CONTRACT_DRAFT", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("\"code\":\"CONFLICT\"").contains("CONTRACT_DRAFT");
    }

    @Test
    @DisplayName("entradas inválidas respondem 400")
    void shouldRejectInvalidInput() {
        assertThat(createRule("TIPO_INEXISTENTE", "IT_A", true).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(createRule("PROPOSAL_ACCEPTANCE", "codigo com espaço", true).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin().postForEntity(BASE_PATH, json("""
                        {"caseType":"PROPOSAL_ACCEPTANCE","requiredDocumentType":"IT_B"}
                        """), String.class).getBody())
                .contains("mandatory");
        assertThat(admin().postForEntity(BASE_PATH, json("{ isto não é json"), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(admin().getForEntity(BASE_PATH + "/" + UUID.randomUUID(), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("o tipo de demanda de uma regra não muda")
    void shouldRejectCaseTypeChange() {
        String id = idOf(createRule("PROPOSAL_ACCEPTANCE", "IT_CASE_TYPE_LOCK", false));

        ResponseEntity<String> response = admin().exchange(
                BASE_PATH + "/" + id,
                HttpMethod.PUT,
                json("""
                        {"caseType":"CONTRACT_SIGNING","requiredDocumentType":"IT_CASE_TYPE_LOCK","mandatory":false}
                        """),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("não pode ser alterado");
    }

    @Test
    @DisplayName("regra em uso não pode ser excluída nem trocar o documento, mas pode virar opcional")
    void shouldProtectRuleInUse() {
        String id = idOf(createRule("PROPOSAL_ACCEPTANCE", "IT_RULE_IN_USE", true));
        LegalCaseEntity legalCase = legalCaseRepository.save(new LegalCaseEntity(
                UUID.randomUUID(), null, LegalCaseType.PROPOSAL_ACCEPTANCE, LegalCaseStatus.EXTRACTING,
                "ana.silva", null, CasePriority.NORMAL, Instant.now(), Instant.now()));
        itemRepository.save(new DocumentChecklistItemEntity(
                UUID.randomUUID(), legalCase.getId(), UUID.fromString(id), ChecklistItemStatus.MISSING, null, Instant.now()));

        ResponseEntity<String> delete = admin().exchange(BASE_PATH + "/" + id, HttpMethod.DELETE, null, String.class);
        ResponseEntity<String> changeDocument = admin().exchange(
                BASE_PATH + "/" + id,
                HttpMethod.PUT,
                json("""
                        {"requiredDocumentType":"IT_OTHER","mandatory":true}
                        """),
                String.class);
        ResponseEntity<String> makeOptional = admin().exchange(
                BASE_PATH + "/" + id,
                HttpMethod.PUT,
                json("""
                        {"requiredDocumentType":"IT_RULE_IN_USE","mandatory":false}
                        """),
                String.class);

        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(delete.getBody()).contains("em uso");
        assertThat(changeDocument.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(makeOptional.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(makeOptional.getBody()).contains("\"mandatory\":false");
        assertThat(ruleRepository.findById(UUID.fromString(id))).isPresent();
    }
}
