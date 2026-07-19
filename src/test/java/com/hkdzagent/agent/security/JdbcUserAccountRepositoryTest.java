package com.hkdzagent.agent.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcUserAccountRepositoryTest {

    private JdbcUserAccountRepository repository;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:users_" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                ""
        );
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE app_users (
                    id UUID PRIMARY KEY,
                    username VARCHAR(128) NOT NULL,
                    password_hash VARCHAR(255) NOT NULL,
                    enabled BOOLEAN NOT NULL,
                    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("CREATE UNIQUE INDEX ux_users_username ON app_users (username)");
        jdbcTemplate.execute("CREATE TABLE app_roles (name VARCHAR(64) PRIMARY KEY)");
        jdbcTemplate.execute("""
                CREATE TABLE app_user_roles (
                    user_id UUID NOT NULL,
                    role_name VARCHAR(64) NOT NULL,
                    PRIMARY KEY (user_id, role_name),
                    FOREIGN KEY (user_id) REFERENCES app_users (id),
                    FOREIGN KEY (role_name) REFERENCES app_roles (name)
                )
                """);
        jdbcTemplate.update("INSERT INTO app_roles (name) VALUES ('USER'), ('ADMIN')");
        repository = new JdbcUserAccountRepository(
                new NamedParameterJdbcTemplate(jdbcTemplate),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource))
        );
    }

    @Test
    void persistsUserAndLoadsRolesCaseInsensitively() {
        UserAccount account = new UserAccount(
                UUID.randomUUID().toString(),
                "Alice",
                "$2a$10$password-hash",
                true,
                Set.of("USER", "ADMIN")
        );

        repository.save(account);

        assertThat(repository.existsByUsername(" alice ")).isTrue();
        assertThat(repository.findByUsername("ALICE"))
                .usingRecursiveComparison()
                .isEqualTo(account);
    }

    @Test
    void returnsNullForUnknownUser() {
        assertThat(repository.findByUsername("missing")).isNull();
        assertThat(repository.existsByUsername("missing")).isFalse();
    }
}
