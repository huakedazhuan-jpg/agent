# Authentication and RBAC

This stage adds a production-oriented authentication baseline. It does not claim complete application security.

## Authentication flow

1. A user submits a username and password to `POST /api/auth/login`.
2. The server normalizes the username, loads the account, and verifies its BCrypt password hash.
3. A successful login returns a short-lived HMAC-SHA-256 JWT containing the user ID, username, and roles.
4. Protected requests send the token as `Authorization: Bearer <token>`.
5. Spring Security validates the signature, issuer, and expiry before applying endpoint role rules.

Invalid usernames and passwords return the same response so the login endpoint does not reveal whether an account exists.

## Endpoint policy

| Endpoint | Access |
| --- | --- |
| `POST /api/auth/login` | Public |
| `GET /api/auth/me` | Authenticated |
| `/api/agent/**` | Authenticated |
| Tool confirmation approve/reject | `ADMIN` |
| `/test/**` | `ADMIN` |
| `POST /api/feishu/webhook` | Public at Spring Security layer; Feishu verification/signature checks still apply |
| Static landing page | Public |
| All unmatched routes | Denied when security is enabled |

## Persistence and bootstrap

Flyway migration `V6__users_and_roles.sql` creates users, roles, and assignments. Usernames are unique case-insensitively in PostgreSQL, and only password hashes are stored.

For the first deployment, configure `AGENT_SECURITY_BOOTSTRAP_USERNAME` and `AGENT_SECURITY_BOOTSTRAP_PASSWORD`. The password must contain at least 12 characters. The bootstrap initializer inserts a missing account but never updates an existing one. Remove the bootstrap password from the runtime environment after the first account has been created.

Local development keeps authentication disabled and uses an in-memory user repository by default. Production startup rejects that configuration and requires:

```properties
AGENT_SECURITY_ENABLED=true
AGENT_SECURITY_USER_REPOSITORY=jdbc
AGENT_SECURITY_JWT_SECRET=<at-least-32-random-bytes>
```

## Deliberate limitations

- RBAC is endpoint-level. Trace, conversation, and approval records do not yet carry an owner/tenant boundary.
- JWT access tokens cannot currently be refreshed or revoked before expiry.
- Signing-key rotation and asymmetric keys are not implemented.
- Login rate limiting, account lockout, password reset, and audit events are not implemented.
- The static console does not yet provide a login/token-management UI.

These are the boundary for the next authorization and security-hardening stages; they must not be represented as completed capabilities on a resume.
