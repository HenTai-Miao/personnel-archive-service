# Unified service port and production endpoint design

**Date:** 2026-07-29

## Goal

Use backend port `18080` consistently in every environment, while keeping production and local frontend hosts distinct.

## Confirmed endpoint

- Production service host: `10.23.157.237`
- Production service port: `18080`
- Production API base URL: `http://10.23.157.237:18080/api/v1`
- Production API origin: `http://10.23.157.237:18080`
- Local API base URL: `http://127.0.0.1:18080/api/v1`
- Local API origin: `http://127.0.0.1:18080`

The server login credential is deployment-only information. It must not be written to either repository, an environment file, build output, test fixture, or log.

## Backend design

The shared `application.yml` and the explicit local and production configuration files all default the service to port `18080`. `application.yml` and `application-prod.yml` retain `${PA_SERVER_PORT:18080}`, so an operations environment variable remains authoritative without rebuilding the service.

The root local launcher waits for `http://127.0.0.1:18080/api/v1`. There is no active backend or client configuration that uses port `8080` after this change.

The service continues to bind according to Spring Boot's normal server-address behavior. Host firewall rules, process supervision, database credentials, token keys, and field-encryption keys remain deployment concerns and are not embedded in source control.

## Frontend design

The tracked `.env.prod` contains:

```dotenv
VITE_API_BASE_URL=http://10.23.157.237:18080/api/v1
VITE_API_ORIGIN=http://10.23.157.237:18080
```

The local `.env.local`, tracked `.env.example`, renderer fallback, Vite fallback, and Electron E2E request interceptors all use `http://127.0.0.1:18080`. The production build remains explicitly driven by `build:prod`, ensuring a distributable client uses the server host while both modes share the same backend port.

The existing Vite configuration continues to validate that `VITE_API_ORIGIN` matches the origin of `VITE_API_BASE_URL`, and injects the production origin into the renderer Content Security Policy.

## Data flow

1. Spring resolves the default HTTP port as `18080` from common configuration for both `dev` and `prod`, unless `PA_SERVER_PORT` explicitly overrides it.
2. The local launcher and development client use `127.0.0.1:18080`.
3. The Windows client is built with Vite mode `prod`.
4. Vite reads `.env.prod`, embeds the versioned API base URL, and injects the matching production origin into the renderer Content Security Policy.
5. The packaged client sends authenticated API requests to `10.23.157.237:18080`.

## Failure handling

- A malformed URL or mismatched origin fails the frontend build through the existing configuration validation.
- If the service is not reachable or port `18080` is blocked, the client keeps the existing network-error presentation; it does not fall back to localhost.
- Production secrets remain required through environment injection. This change does not add defaults for database or security secrets.

## Verification

- Add frontend regression coverage for the local `18080` endpoint and explicit production build contract.
- Build the renderer in `prod` mode and verify the emitted CSP and request base point to `10.23.157.237:18080` rather than `127.0.0.1`.
- Update the backend configuration test so common, `dev`, and `prod` configurations all resolve port `18080`, while `PA_SERVER_PORT` still overrides the shared value.
- Run the frontend lint, type check, unit tests, and production build.
- Run the backend configuration and all non-database tests plus JAR packaging. Do not pull Testcontainers images or point automated tests at the user's existing `local-mysql` database.

## Out of scope

- Logging into or deploying files to `10.23.157.237`.
- Installing Java, MySQL, a reverse proxy, TLS certificates, firewall rules, or a process supervisor.
- Storing the supplied server password.
