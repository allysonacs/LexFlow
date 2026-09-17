package com.lexflow.api.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.api.AbstractApiIT;
import com.lexflow.infrastructure.persistence.entity.KnowledgeBaseChunkEntity;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseChunkJpaRepository;
import com.lexflow.infrastructure.persistence.repository.KnowledgeBaseSourceJpaRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * API da base normativa (Prompt 12), com autenticação real e o banco com pgvector.
 *
 * <p>As fontes criadas aqui são removidas ao fim de cada teste: o banco é compartilhado com os demais
 * testes da API, e uma norma esquecida mudaria o que a recuperação devolve para eles.
 *
 * <p>Requer Docker em execução.
 */
class KnowledgeBaseIT extends AbstractApiIT {

    private static final String SOURCES_PATH = KnowledgeBaseController.BASE_PATH + "/sources";

    /** Credenciais do perfil dev, usadas pelos testes. */
    private static final String ADMIN_USER = "admin";

    private static final String ADMIN_PASSWORD = "lexflow-admin";

    private static final String ALCADAS =
            """
            Art. 1º A assinatura de contratos com valor superior a cem mil reais depende de aprovação prévia do diretor jurídico.

            Art. 2º Contratos de valor igual ou inferior a cem mil reais podem ser assinados pelo gerente da área demandante.
            """;

    private static final String ACORDOS =
            """
            Art. 1º O pagamento de acordo judicial depende de homologação pelo juízo competente.

            Art. 2º A quitação do acordo exige comprovante de depósito e termo de quitação assinado pelas partes.
            """;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KnowledgeBaseSourceJpaRepository sourceRepository;

    @Autowired
    private KnowledgeBaseChunkJpaRepository chunkRepository;

    private final List<UUID> createdSources = new ArrayList<>();

    @AfterEach
    void removeCreatedSources() {
        for (UUID sourceId : createdSources) {
            List<KnowledgeBaseChunkEntity> chunks = chunkRepository.findBySourceIdOrderByChunkIndexAsc(sourceId);
            chunkRepository.deleteAll(chunks);
            sourceRepository.deleteById(sourceId);
        }
        createdSources.clear();
    }

    @Test
    @DisplayName("a base normativa é restrita ao papel ADMIN, como as demais rotas administrativas")
    void shouldRequireAdminRole() {
        ResponseEntity<String> semCredencial =
                restTemplate.postForEntity(SOURCES_PATH, json(sourceBody("Norma", ALCADAS)), String.class);
        ResponseEntity<String> buscaSemCredencial =
                restTemplate.getForEntity(KnowledgeBaseController.BASE_PATH + "/search?query=contrato", String.class);

        assertThat(semCredencial.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(buscaSemCredencial.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(semCredencial.getBody()).contains("UNAUTHORIZED");
    }

    @Test
    @DisplayName("uma norma enviada como texto é indexada e volta com os seus trechos")
    void shouldIndexSourceSentAsText() {
        ResponseEntity<String> criada =
                admin().postForEntity(SOURCES_PATH, json(sourceBody("IT Política de Alçadas", ALCADAS)), String.class);
        UUID id = trackCreated(criada);

        assertThat(criada.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(criada.getHeaders().getLocation()).asString().endsWith(id.toString());
        assertThat(criada.getBody()).contains("\"chunkCount\"", "\"sourceType\":\"INTERNAL_POLICY\"");

        ResponseEntity<String> detalhe = admin().getForEntity(SOURCES_PATH + "/" + id, String.class);

        assertThat(detalhe.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detalhe.getBody()).contains("diretor jurídico", "\"indexed\":true");
        assertThat(chunkRepository.findBySourceIdOrderByChunkIndexAsc(id)).isNotEmpty();
    }

    @Test
    @DisplayName("uma norma enviada como arquivo de texto é indexada do mesmo jeito")
    void shouldIndexSourceSentAsFile() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("title", "IT Política de Acordos");
        form.add("sourceType", "INTERNAL_POLICY");
        form.add("effectiveDate", "2026-01-01");
        form.add("file", new ByteArrayResource(ACORDOS.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "acordos.txt";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<String> criada =
                admin().postForEntity(SOURCES_PATH, new HttpEntity<>(form, headers), String.class);
        UUID id = trackCreated(criada);

        assertThat(criada.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(admin().getForEntity(SOURCES_PATH + "/" + id, String.class).getBody())
                .contains("homologação", "\"effectiveDate\":\"2026-01-01\"");
    }

    @Test
    @DisplayName("a busca traz os trechos da norma relacionada à consulta, com o texto e a fonte")
    void shouldRetrieveRelevantChunks() {
        UUID alcadas = trackCreated(
                admin().postForEntity(SOURCES_PATH, json(sourceBody("IT Política de Alçadas", ALCADAS)), String.class));
        trackCreated(
                admin().postForEntity(SOURCES_PATH, json(sourceBody("IT Política de Acordos", ACORDOS)), String.class));

        ResponseEntity<String> busca = admin().getForEntity(
                KnowledgeBaseController.BASE_PATH
                        + "/search?limit=3&query=Podemos assinar esse contrato de valor superior a cem mil reais?",
                String.class);

        assertThat(busca.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(busca.getBody()).contains(alcadas.toString(), "diretor jurídico", "\"similarity\"");
    }

    @Test
    @DisplayName("um tipo de fonte desconhecido e um texto vazio são recusados com 400")
    void shouldRejectInvalidRequests() {
        ResponseEntity<String> tipoInvalido = admin().postForEntity(
                SOURCES_PATH,
                json("""
                     {"title": "IT Norma", "sourceType": "PORTARIA", "text": "Art. 1º ..."}
                     """),
                String.class);
        ResponseEntity<String> textoVazio = admin().postForEntity(
                SOURCES_PATH,
                json("""
                     {"title": "IT Norma", "sourceType": "LAW", "text": "   "}
                     """),
                String.class);

        assertThat(tipoInvalido.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tipoInvalido.getBody()).contains("INVALID_REQUEST");
        assertThat(textoVazio.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("uma fonte inexistente responde 404")
    void shouldAnswerNotFoundForUnknownSource() {
        ResponseEntity<String> resposta =
                admin().getForEntity(SOURCES_PATH + "/" + UUID.randomUUID(), String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resposta.getBody()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("a listagem de fontes é paginada")
    void shouldListSourcesPaginated() {
        trackCreated(
                admin().postForEntity(SOURCES_PATH, json(sourceBody("IT Política de Alçadas", ALCADAS)), String.class));

        ResponseEntity<String> lista = admin().getForEntity(SOURCES_PATH + "?page=0&size=100", String.class);

        assertThat(lista.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lista.getBody()).contains("\"content\"", "\"totalElements\"", "IT Política de Alçadas");
    }

    private TestRestTemplate admin() {
        return restTemplate.withBasicAuth(ADMIN_USER, ADMIN_PASSWORD);
    }

    private static String sourceBody(String title, String text) {
        return """
                {"title": %s, "sourceType": "INTERNAL_POLICY", "effectiveDate": "2026-01-01", "text": %s}
                """.formatted(jsonString(title), jsonString(text));
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private UUID trackCreated(ResponseEntity<String> response) {
        UUID id = UUID.fromString(response.getBody().replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
        createdSources.add(id);
        return id;
    }
}
