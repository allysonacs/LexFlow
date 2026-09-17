package com.lexflow.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexflow.api.error.ApiErrorCodes;
import com.lexflow.api.error.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Segurança da API.
 *
 * <p><strong>Escopo deliberadamente mínimo.</strong> Só {@code /api/v1/admin/**} exige autenticação,
 * por HTTP Basic, com o papel {@code ADMIN}. As demais rotas continuam abertas, como estavam: o
 * controle de acesso por papel da seção 12 ({@code ANALYST}, {@code LEGAL_REVIEWER}, {@code ADMIN})
 * depende de uma decisão sobre o provedor de identidade que ainda não foi tomada. Quando ela vier,
 * a troca fica contida nesta classe.
 *
 * <p>A API é stateless: sem sessão e sem CSRF, que só faz sentido para autenticação por cookie. As
 * respostas 401 e 403 seguem o mesmo formato de erro do resto da API.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminSecurityProperties.class)
public class SecurityConfiguration {

    /** Prefixo das rotas administrativas. */
    public static final String ADMIN_PATHS = "/api/v1/admin/**";

    /**
     * Base normativa do RAG (Prompt 12).
     *
     * <p>Protegida como rota administrativa, embora fora de {@code /admin}: indexar ou reindexar uma
     * norma muda o fundamento de toda resposta futura da IA. O caminho vem do Prompt 12.
     */
    public static final String KNOWLEDGE_BASE_PATHS = "/api/v1/knowledge-base/**";

    public static final String ADMIN_ROLE = "ADMIN";

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper, Clock clock)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(ADMIN_PATHS, KNOWLEDGE_BASE_PATHS).hasRole(ADMIN_ROLE)
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, e) -> writeError(
                        objectMapper, clock, request, response, HttpStatus.UNAUTHORIZED, ApiErrorCodes.UNAUTHORIZED,
                        "Autenticação obrigatória")))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, e) -> writeError(
                                objectMapper, clock, request, response, HttpStatus.UNAUTHORIZED,
                                ApiErrorCodes.UNAUTHORIZED, "Autenticação obrigatória"))
                        .accessDeniedHandler((request, response, e) -> writeError(
                                objectMapper, clock, request, response, HttpStatus.FORBIDDEN,
                                ApiErrorCodes.FORBIDDEN, "Acesso restrito ao papel " + ADMIN_ROLE)))
                .formLogin(form -> form.disable())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(AdminSecurityProperties properties, PasswordEncoder passwordEncoder) {
        String password = properties.isPasswordEncoded()
                ? properties.password()
                : passwordEncoder.encode(properties.password());
        return new InMemoryUserDetailsManager(User.withUsername(properties.username())
                .password(password)
                .roles(ADMIN_ROLE)
                .build());
    }

    private static void writeError(
            ObjectMapper objectMapper,
            Clock clock,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        if (status == HttpStatus.UNAUTHORIZED) {
            // Sem este cabeçalho o cliente não sabe qual esquema de autenticação usar.
            response.setHeader("WWW-Authenticate", "Basic realm=\"lexflow-admin\"");
        }
        objectMapper.writeValue(
                response.getOutputStream(),
                ApiErrorResponse.of(code, message, clock.instant(), request.getRequestURI()));
    }
}
