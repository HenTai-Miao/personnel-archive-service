# Production endpoint environment design

**Date:** 2026-07-29

## Goal

Provide one explicit production configuration for the Java service and the canonical Electron client without changing the existing local-development endpoint.

## Confirmed endpoint

- Production service host: `10.141.29.96`
- Production service port: `18080`
- Production API base URL: `http://10.141.29.96:18080/api/v1`
- Production API origin: `http://10.141.29.96:18080`

The server login credential is deployment-only information. It must not be written to either repository, an environment file, build output, test fixture, or log.

## Backend design

Add `src/main/resources/application-prod.yml`. When Spring profile `prod` is active, the service listens on `${PA_SERVER_PORT:18080}`. The environment variable remains authoritative so operations can override the port without rebuilding the service.

The shared `application.yml` keeps its current `${PA_SERVER_PORT:8080}` default. Consequently, the root local launcher and ordinary local development continue to use port `8080`; only an explicit `prod` profile gets the five-digit default.

The service continues to bind according to Spring Boot's normal server-address behavior. Host firewall rules, process supervision, database credentials, token keys, and field-encryption keys remain deployment concerns and are not embedded in source control.

## Frontend design

Add a tracked `.env.prod` containing:

```dotenv
VITE_API_BASE_URL=http://10.141.29.96:18080/api/v1
VITE_API_ORIGIN=http://10.141.29.96:18080
```

Add an explicit `build:prod` script that runs the existing type check and `electron-vite build --mode prod`. Change the Windows packaging script to build through `build:prod`, ensuring a distributable client cannot silently retain the local loopback endpoint. The ordinary development command and `.env.local` remain unchanged.

The existing Vite configuration continues to validate that `VITE_API_ORIGIN` matches the origin of `VITE_API_BASE_URL`, and injects the production origin into the renderer Content Security Policy.

## Data flow

1. Operations starts the Java service with Spring profile `prod`.
2. Spring resolves the default HTTP port as `18080`, unless `PA_SERVER_PORT` explicitly overrides it.
3. The Windows client is built with Vite mode `prod`.
4. Vite reads `.env.prod`, embeds the versioned API base URL, and restricts renderer connections to the matching production origin.
5. The packaged client sends authenticated API requests to `10.141.29.96:18080`.

## Failure handling

- A malformed URL or mismatched origin fails the frontend build through the existing configuration validation.
- If the service is not reachable or port `18080` is blocked, the client keeps the existing network-error presentation; it does not fall back to localhost.
- Production secrets remain required through environment injection. This change does not add defaults for database or security secrets.

## Verification

- Add a frontend regression test for the explicit production build contract.
- Build the renderer in `prod` mode and verify the emitted CSP and request base point to `10.141.29.96:18080` rather than `127.0.0.1`.
- Add a backend configuration test that loads the `prod` profile and observes port `18080` while the normal configuration remains `8080`.
- Run the frontend lint, type check, unit tests, and production build.
- Run backend `./mvnw verify` with Java 21 and Docker-backed MySQL tests available.

## Out of scope

- Logging into or deploying files to `10.141.29.96`.
- Installing Java, MySQL, a reverse proxy, TLS certificates, firewall rules, or a process supervisor.
- Storing the supplied server password.
