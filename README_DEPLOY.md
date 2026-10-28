# Vendoryx Backend — Deploy Notes

Java 17 embedded Tomcat app, Docker / Render ready.

## Required environment variables

| Variable | Description |
|----------|-------------|
| `DB_URL` (or `VENDORYX_DB_URL`) | JDBC URL, e.g. `jdbc:postgresql://HOST/neondb?sslmode=require` (sslmode appended if missing) |
| `DB_USER` (or `VENDORYX_DB_USER`) | Database username |
| `DB_PASSWORD` (or `VENDORYX_DB_PASSWORD`) | Database password |
| `VENDORYX_JWT_SECRET` | JWT signing secret (**min 32 characters**) — still required for admin/shop APIs |

## Optional

| Variable | Default | Description |
|----------|---------|-------------|
| `CORS_ALLOWED_ORIGINS` (or `VENDORYX_CORS_ORIGIN`) | `http://localhost:4200` | Comma-separated allowed origins (no `*`) |
| `PORT` | `8080` | HTTP listen port (bind `0.0.0.0`) |

## Guest checkout (one-time Neon SQL)

Run `sql/guest_checkout_columns.sql` before using `POST /api/orders/guest`.
| `VENDORYX_WEBAPP_DIR` | auto | Override webapp path (container: `/app/webapp`) |
| `VENDORYX_CLASSES_DIR` | auto | Override classes path (container: `/app/classes`) |

## Health check

`GET /health` → `200` `{"status":"ok"}` (no auth, no DB).

## Local Docker

```bash
docker build -t vendoryx-backend .
docker run --rm -p 8080:8080 \
  -e VENDORYX_DB_URL=... \
  -e VENDORYX_DB_USER=... \
  -e VENDORYX_DB_PASSWORD=... \
  -e VENDORYX_JWT_SECRET=your-32-char-minimum-secret-here \
  -e VENDORYX_CORS_ORIGIN="https://vendoryx-frontend.pages.dev,http://localhost:4200" \
  vendoryx-backend
```

Matching request `Origin` is echoed in `Access-Control-Allow-Origin` (never `*`). OPTIONS preflight returns `204` without a token.


## Local without Docker

```bash
# set the four required env vars first, then:
mvn clean package
java -Xmx320m -XX:+UseSerialGC -cp "target/classes;target/dependency/*" com.vendoryx.AppServer
```

(On Linux/macOS use `:` instead of `;` in the classpath.)
