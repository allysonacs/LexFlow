package com.lexflow;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexflow.api.AbstractApiIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Verifica que o contexto da aplicação sobe e que o health check responde {@code UP}.
 *
 * <p>Desde o Prompt 03 a aplicação depende de um PostgreSQL com as migrations aplicadas, então o
 * teste sobe containers reais em vez de tentar conectar aos serviços locais. É preciso ter o Docker
 * em execução.
 */
class LexFlowApplicationTest extends AbstractApiIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointShouldReturnUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void healthEndpointShouldReportDatabaseComponent() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getBody()).contains("\"db\"");
    }
}
