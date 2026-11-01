# Docker Desktop Compose Start repair — 22/10/2026

The Start button attempted project `capstone-project-progress-tracking-system-be`, but the active backend
containers belonged to `capstone-debug`. Fixing a helper script alone did not repair that project identity.

The root `docker-compose.yml` now declares the expected name and includes `docker-compose.desktop.yml`
with `.env.lan` for interpolation. The desktop stack contains the current backend and PostgreSQL on port 8081.
The previous infrastructure configuration is preserved in `docker-compose.infra.yml` under `capstone-infra`.

Before switching, a custom-format PostgreSQL backup was saved to
`target/db-backups/before-desktop-project-fix-20261022.dump` (ignored by Git).
Baseline: 28 users, 24 semester/student rows, 3 groups.

The new PostgreSQL container reuses `capstone-debug_pgdata`; uploads reuse `capstone-debug_uploads`.
Both are explicit external volumes. Old `capstone-debug` app/database containers are stopped, not deleted.
Do not start the old database while the new database is using the same volume. No volume was removed.
The existing `capstone-lan` stack is separate and was not changed.

Start normally in Docker Desktop by choosing `capstone-project-progress-tracking-system-be`,
or run `start-backend.cmd`. The latter waits for healthy services and recreates missing containers.
The FE proxy and Cloudflare backend tunnel still point to host port 8081.

Verification: the exact command `docker compose -p capstone-project-progress-tracking-system-be start`
returned exit code 0. A real Stop -> Start cycle also succeeded; both app and postgres became healthy,
Swagger returned HTTP 200, and database row counts matched the baseline. The FE API proxy still responds.

Recovery if the new stack ever fails: stop its app and postgres services first; only then restart
`capstone-debug-postgres-1` and `capstone-debug-app-1`. Never run two PostgreSQL processes on the same data directory.
