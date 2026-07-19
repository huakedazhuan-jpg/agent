package com.hkdzagent.agent.security;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class JdbcUserAccountRepository implements UserAccountRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionOperations transactions;

    public JdbcUserAccountRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            TransactionOperations transactions
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactions = transactions;
    }

    @Override
    public UserAccount findByUsername(String username) {
        List<UserAccount> accounts = jdbcTemplate.query("""
                SELECT id, username, password_hash, enabled
                FROM app_users
                WHERE lower(username) = :username
                """,
                new MapSqlParameterSource("username", normalize(username)),
                (rs, rowNum) -> new UserAccount(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getBoolean("enabled"),
                        findRoles(rs.getString("id"))
                ));
        return accounts.isEmpty() ? null : accounts.get(0);
    }

    @Override
    public boolean existsByUsername(String username) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM app_users
                WHERE lower(username) = :username
                """,
                new MapSqlParameterSource("username", normalize(username)),
                Integer.class);
        return count != null && count > 0;
    }

    @Override
    public UserAccount save(UserAccount account) {
        return transactions.execute(status -> {
            jdbcTemplate.update("""
                    INSERT INTO app_users (id, username, password_hash, enabled)
                    VALUES (:id, :username, :passwordHash, :enabled)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", UUID.fromString(account.id()))
                            .addValue("username", account.username())
                            .addValue("passwordHash", account.passwordHash())
                            .addValue("enabled", account.enabled()));
            for (String role : account.roles()) {
                jdbcTemplate.update("""
                        INSERT INTO app_user_roles (user_id, role_name)
                        VALUES (:userId, :roleName)
                        """,
                        new MapSqlParameterSource()
                                .addValue("userId", UUID.fromString(account.id()))
                                .addValue("roleName", role));
            }
            return account;
        });
    }

    private Set<String> findRoles(String userId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList("""
                SELECT role_name
                FROM app_user_roles
                WHERE user_id = :userId
                ORDER BY role_name ASC
                """,
                new MapSqlParameterSource("userId", UUID.fromString(userId)),
                String.class));
    }

    private String normalize(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
