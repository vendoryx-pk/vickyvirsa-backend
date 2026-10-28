# Vendoryx Frontend Integration Prompt (Angular)

Copy-paste this prompt into your frontend AI / Cursor chat.

---

## Goal

Integrate this Angular app (`http://localhost:4200`) with the Vendoryx Java backend running at:

**Base URL:** `http://localhost:8080`

Backend already has CORS for `http://localhost:4200`.  
Do NOT hardcode login credentials in the frontend. Always call the APIs below. Login and updates must go through the backend so the Neon PostgreSQL DB is the source of truth.

---

## APIs

### 1) Login — check username + password from DB

- **Method:** `POST`
- **URL:** `http://localhost:8080/api/auth/login`
- **Headers:** `Content-Type: application/json`
- **Body:**
```json
{
  "username": "YOUR_USERNAME",
  "password": "YOUR_PASSWORD"
}
```

**Success (HTTP 200):**
```json
{
  "status": "success",
  "success": true,
  "message": "...",
  "data": {
    "userId": 1,
    "username": "example_user",
    "fullName": "Example User",
    "email": "user@email.com",
    "role": "SHOP_USER",
    "allowedPages": [
      {
        "pageName": "Dashboard",
        "routePath": "/dashboard",
        "icon": "dashboard"
      }
    ]
  }
}
```

**Fail (HTTP 401 / 400 / 500):**
```json
{
  "status": "not_success",
  "success": false,
  "message": "Invalid username or password"
}
```

**Frontend requirements after login:**
1. Save `data` (user object) in a service / localStorage / sessionStorage.
2. Especially keep `userId` — required for profile/password update.
3. Use `allowedPages` to show/hide menu items and protect routes (`routePath`).
4. On `success === false` or HTTP error, show `message` to the user. Do not navigate to dashboard.

---

### 2) Update profile / password — write changes to DB

- **Method:** `PUT` (preferred) or `POST`
- **URL:** `http://localhost:8080/api/users/update`
- **Headers:** `Content-Type: application/json`
- **Body examples:**

Update name + email:
```json
{
  "userId": 1,
  "fullName": "Example Updated",
  "email": "new@email.com"
}
```

Update password only (currentPassword required; never send password_hash):
```json
{
  "newPassword": "YOUR_NEW_PASSWORD",
  "currentPassword": "YOUR_CURRENT_PASSWORD"
}
```

Update everything:
```json
{
  "fullName": "Example Updated",
  "email": "new@email.com",
  "newPassword": "YOUR_NEW_PASSWORD",
  "currentPassword": "YOUR_CURRENT_PASSWORD"
}
```

Accepted new-password field names (any one): `password` | `newPassword`  
Accepted current-password field names (any one): `currentPassword` | `oldPassword`  
Do **not** send `password_hash` — name/email-only saves must omit password fields so the DB hash is untouched.  
`userId` comes from the JWT (body userId is ignored).

**Success (HTTP 200):**
```json
{
  "status": "success",
  "success": true,
  "message": "Password updated successfully",
  "data": { "...updated user without password..." }
}
```

**Fail (HTTP 400 / 404 / 500):**
```json
{
  "status": "not_success",
  "success": false,
  "message": "User not found — update not success"
}
```

**Frontend requirements after update:**
1. Always send `userId` from the logged-in user.
2. If `success === true`, show success toast/snackbar using `message`, then refresh local user state from `data`.
3. If `success === false`, show error using `message`. Do not pretend update worked.
4. After password change succeeds, either keep session or force re-login with the new password (prefer re-login for clarity).

---

## Suggested Angular structure

```
src/app/core/
  services/auth.api.ts      // HttpClient calls
  services/auth.store.ts    // hold current user
  guards/auth.guard.ts      // block routes if not logged in
  guards/page.guard.ts      // allow only allowedPages.routePath
```

### Example service methods

```ts
login(username: string, password: string) {
  return this.http.post<ApiResponse<User>>(
    'http://localhost:8080/api/auth/login',
    { username, password }
  );
}

updateUser(payload: {
  userId: number;
  fullName?: string;
  email?: string;
  password?: string;
}) {
  return this.http.put<ApiResponse<User>>(
    'http://localhost:8080/api/users/update',
    payload
  );
}
```

### Example response types

```ts
interface ApiResponse<T> {
  status: 'success' | 'not_success';
  success: boolean;
  message: string;
  data?: T;
}

interface PageAccess {
  pageName: string;
  routePath: string;
  icon: string;
}

interface User {
  userId: number;
  username: string;
  fullName: string;
  email: string;
  role: string;
  allowedPages: PageAccess[];
}
```

---

## UX rules

1. Login form: username + password → call login API only (no fake/local auth).
2. Profile / settings form: bind fullName, email, optional new password → call update API.
3. Handle HTTP errors in subscribe `error` callback AND check `success` / `status` in body.
4. Loading states on buttons while request is in progress.
5. Never store plain password in localStorage after login.
6. Keep `environment.apiBaseUrl = 'http://localhost:8080'` so URL is not scattered.

---

## Quick test checklist

- [ ] Backend `AppServer` running on port 8080
- [ ] Angular on port 4200
- [ ] Login with real DB user works and returns `allowedPages`
- [ ] Wrong password shows `not_success` message
- [ ] Update fullName/email persists (reload / re-login still shows new values)
- [ ] Update password works; next login uses the NEW password
- [ ] Menu/routes respect `allowedPages`

---

## Do NOT

- Do not hardcode real usernames / passwords as “always logged in”
- Do not update user only in Angular memory without calling `/api/users/update`
- Do not call wrong URLs like `/vendoryx-backend/...` (context root is empty)
- Do not ignore `success: false` responses
