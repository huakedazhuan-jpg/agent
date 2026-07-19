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

Flyway migration `V7__resource_ownership.sql` adds an `owner_key` to conversations, Agent traces, and tool approvals. Existing records are assigned `legacy:unowned`; they are not silently transferred to a real user.

For the first deployment, configure `AGENT_SECURITY_BOOTSTRAP_USERNAME` and `AGENT_SECURITY_BOOTSTRAP_PASSWORD`. The password must contain at least 12 characters. The bootstrap initializer inserts a missing account but never updates an existing one. Remove the bootstrap password from the runtime environment after the first account has been created.

Local development keeps authentication disabled and uses an in-memory user repository by default. Production startup rejects that configuration and requires:

```properties
AGENT_SECURITY_ENABLED=true
AGENT_SECURITY_USER_REPOSITORY=jdbc
AGENT_SECURITY_JWT_SECRET=<at-least-32-random-bytes>
```

## Object ownership

Authentication identifies the caller, while an Actor key identifies the owner of runtime data:

```text
user:<JWT subject>
feishu:<openId>
local:anonymous
legacy:unowned
```

Web conversation IDs are internally namespaced with the authenticated user before they reach Chat Memory. Therefore two users can safely use the same client-provided `sessionId`. PostgreSQL conversation uniqueness also includes `owner_key`.

Trace lookup, recent Trace lists, and pending approval lists include the current Actor key in their repository query. A lookup for another user's Trace returns 404 rather than revealing that the resource exists.

Tool approval decisions remain an explicit `ADMIN` capability. An administrator may approve or reject another user's pending tool request, while ordinary users can only list their own pending requests and cannot make approval decisions.

## Deliberate limitations

- Resource ownership is per Actor, not per organization or tenant; organization membership and tenant administration are not implemented.
- Ownership is implemented for chat memory, traces, and tool approvals, not as a generic policy engine for future resource types.
- JWT access tokens cannot currently be refreshed or revoked before expiry.
- Signing-key rotation and asymmetric keys are not implemented.
- Login rate limiting, account lockout, password reset, and audit events are not implemented.
- The static console does not yet provide a login/token-management UI.

These limitations must not be represented as completed capabilities on a resume.
