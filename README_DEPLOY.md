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

## Optional (products / email)

| Variable | Description |
|----------|-------------|
| `CLOUDINARY_CLOUD_NAME` / `CLOUDINARY_API_KEY` / `CLOUDINARY_API_SECRET` | Signed uploads |
| `RESEND_API_KEY` / `MAIL_FROM` / `OWNER_NOTIFY_EMAIL` | Owner alert on new guest order |
| `ADMIN_URL` | Link included in owner email body |

## Health check

`GET /health` and `GET /api/health` → `200` `{"status":"ok"}` (no auth, no DB).

## Render

1. Build: `mvn clean package -DskipTests`
2. Start: `java -jar target/vendoryx-backend.jar`
3. Set required env vars in the Render dashboard (no secrets in the repo).
4. Health check path: `/api/health` (or `/health`).

## Local Docker

```bash
docker build -t vendoryx-backend .
docker run --rm -p 8080:8080 \
  -e DB_URL=... \
  -e DB_USER=... \
  -e DB_PASSWORD=... \
  -e VENDORYX_JWT_SECRET=your-32-char-minimum-secret-here \
  -e CORS_ALLOWED_ORIGINS="https://vickyvirsa-frontend.pages.dev,http://localhost:4200" \
  vendoryx-backend
```

Matching request `Origin` is echoed in `Access-Control-Allow-Origin` (never `*`). OPTIONS preflight returns `204` without a token.

## Local without Docker

```bash
# set required env vars (or use gitignored .env), then:
mvn clean package -DskipTests
java -Xmx320m -XX:+UseSerialGC -jar target/vendoryx-backend.jar
```
