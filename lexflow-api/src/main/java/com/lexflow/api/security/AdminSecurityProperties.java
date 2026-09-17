package com.lexflow.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credenciais do administrador, lidas de {@code lexflow.security.admin}.
 *
 * <p>A senha pode vir pronta no formato do Spring Security ({@code {bcrypt}$2a$...}) ou em texto;
 * no segundo caso ela é transformada em hash na inicialização, e o texto nunca é comparado
 * diretamente. Em produção as duas propriedades vêm só de variáveis de ambiente, sem valor padrão: sem
 * elas a aplicação não sobe.
 */
@ConfigurationProperties(prefix = "lexflow.security.admin")
public record AdminSecurityProperties(String username, String password) {

    public AdminSecurityProperties {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("lexflow.security.admin.username é obrigatório");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("lexflow.security.admin.password é obrigatório");
        }
        username = username.strip();
    }

    /** Indica se a senha já está no formato {@code {id}hash} do Spring Security. */
    public boolean isPasswordEncoded() {
        return password.startsWith("{") && password.indexOf('}') > 1;
    }

    @Override
    public String toString() {
        return "AdminSecurityProperties[username=%s, password=***]".formatted(username);
    }
}
