---
title: VendoryX Backend
emoji: 🏪
colorFrom: green
colorTo: blue
sdk: docker
app_port: 8080
pinned: false
---

# VendoryX Backend

Java 17 embedded Tomcat API for VendoryX (shops, catalog, orders).

## Hugging Face Spaces

- **SDK:** Docker  
- **Port:** `8080` (`PORT` env optional; defaults to 8080)  
- **Health:** `GET /health` → `{"status":"ok"}`

### Required Space secrets / variables

Set these in the Space **Settings → Variables and secrets**:

- `VENDORYX_DB_URL`
- `VENDORYX_DB_USER`
- `VENDORYX_DB_PASSWORD`
- `VENDORYX_JWT_SECRET` (min 32 characters)
- `VENDORYX_CORS_ORIGIN` (comma-separated origins, e.g. `https://vendoryx-frontend.pages.dev,http://localhost:4200`)

More detail: see [README_DEPLOY.md](README_DEPLOY.md).
