package com.hkdzagent.agent.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@Configuration
public class SecurityConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "agent.security", name = "user-repository", havingValue = "jdbc")
    public JdbcUserAccountRepository jdbcUserAccountRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        return new JdbcUserAccountRepository(jdbcTemplate, new TransactionTemplate(transactionManager));
    }

    @Bean
    @ConditionalOnMissingBean(UserAccountRepository.class)
    public InMemoryUserAccountRepository inMemoryUserAccountRepository() {
        return new InMemoryUserAccountRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.security", name = "enabled", havingValue = "true")
    public BootstrapUserInitializer bootstrapUserInitializer(
            UserAccountRepository repository,
            PasswordEncoder passwordEncoder,
            AgentSecurityProperties properties
    ) {
        return new BootstrapUserInitializer(repository, passwordEncoder, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.security", name = "enabled", havingValue = "true")
    public JwtEncoder jwtEncoder(AgentSecurityProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(properties)));
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.security", name = "enabled", havingValue = "true")
    public JwtDecoder jwtDecoder(AgentSecurityProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
        return decoder;
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.security", name = "enabled", havingValue = "true")
    public JwtTokenService jwtTokenService(JwtEncoder encoder, AgentSecurityProperties properties) {
        return new JwtTokenService(encoder, properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.security", name = "enabled", havingValue = "true")
    public AuthenticationService authenticationService(
            UserAccountRepository repository,
            PasswordEncoder passwordEncoder,
            JwtTokenService tokenService
    ) {
        return new AuthenticationService(repository, passwordEncoder, tokenService);
    }

    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "agent.security", name = "enabled", havingValue = "true")
    public SecurityFilterChain authenticatedSecurityFilterChain(HttpSecurity http) throws Exception {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authorities);

        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/feishu/webhook").permitAll()
                        .requestMatchers("/api/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST,
                                "/api/agent/tool-confirmations/*/approve",
                                "/api/agent/tool-confirmations/*/reject").hasRole("ADMIN")
                        .requestMatchers("/api/agent/**").authenticated()
                        .requestMatchers("/test/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter)))
                .build();
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(prefix = "agent.security", name = "enabled", havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain developmentSecurityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    private SecretKey secretKey(AgentSecurityProperties properties) {
        String secret = properties.jwt().secret();
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("agent security jwt secret must contain at least 32 bytes");
        }
        return new SecretKeySpec(
                secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"
        );
    }
}
