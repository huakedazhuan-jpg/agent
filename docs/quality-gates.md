# Quality Gates

This project is a production-designed resume project that is still being hardened for deployment. The current quality gate is intentionally small and enforceable.

## Required checks

Every pull request or push to `main` / `master` must pass the GitHub Actions workflow in `.github/workflows/ci.yml`.

The CI workflow currently verifies:

```bash
./mvnw -B --no-transfer-progress test
./mvnw -B --no-transfer-progress -DskipTests package
```

These commands are the minimum merge gate for the current project phase. A change is not considered ready if tests fail, the application cannot be packaged, or production configuration fail-fast tests are broken.

## Current scope

- Java runtime: Temurin JDK 21 on GitHub-hosted Ubuntu runner
- Dependency cache: Maven cache through `actions/setup-java`
- Permissions: read-only repository contents
- Triggers: push, pull request, and manual workflow dispatch

## Not yet covered

The current CI is a baseline gate, not a full production release pipeline. These checks are planned for later phases:

- PostgreSQL and Redis integration tests
- Testcontainers-backed persistence tests
- Static analysis and formatting checks
- Coverage threshold
- Docker image build
- Frontend build and browser e2e tests
- Security scanning
- Deployment or release automation

Do not describe this project as having a complete CI/CD pipeline until those gaps are addressed.
