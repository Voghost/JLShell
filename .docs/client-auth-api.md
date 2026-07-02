# JLShell Client Auth API

This document is for the JLShell desktop client agent that implements website
account login, session keepalive, and logout.

## Base URL

Production example:

```text
https://jlshell.oomn.net
```

All protected requests use:

```http
Authorization: Bearer <token>
```

The token is a JWT issued by the website backend. Treat it as a secret.

## Login

```http
POST /api/v1/account/login
Content-Type: application/json
```

Request:

```json
{
  "username": "alice",
  "password": "user password"
}
```

`username` can be a username or email address.

Response `200`:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresAt": "2026-07-02T12:00:00Z",
  "account": {
    "id": "7cfe1f88-0f95-4b7d-b4b9-55f1d4415b5f",
    "username": "alice",
    "email": "alice@example.com",
    "role": "user",
    "passwordChangeRequired": false,
    "connectionCount": 0,
    "terminalCount": 0
  }
}
```

Failure:

- `401`: wrong username/password.
- `403`: request was authenticated but not allowed for that resource.

Client behavior:

- Store `token`, `expiresAt`, and `account` in the local profile.
- Store the token in the OS secure store when available.
- Do not log the token.

## Register

```http
POST /api/v1/account/register
Content-Type: application/json
```

Request:

```json
{
  "username": "alice",
  "email": "alice@example.com",
  "password": "user password"
}
```

Response is the same shape as login.

Failure:

- `409`: username or email already exists.

## Current Account

```http
GET /api/v1/account/me
Authorization: Bearer <token>
```

Response `200`:

```json
{
  "id": "7cfe1f88-0f95-4b7d-b4b9-55f1d4415b5f",
  "username": "alice",
  "email": "alice@example.com",
  "role": "user",
  "passwordChangeRequired": false,
  "connectionCount": 0,
  "terminalCount": 0
}
```

Use this after app startup to validate a saved token.

## Heartbeat / Token Renewal

```http
POST /api/v1/account/heartbeat
Authorization: Bearer <token>
```

Response is the same shape as login and contains a fresh token:

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresAt": "2026-07-02T20:00:00Z",
  "account": {
    "id": "7cfe1f88-0f95-4b7d-b4b9-55f1d4415b5f",
    "username": "alice",
    "email": "alice@example.com",
    "role": "user",
    "passwordChangeRequired": false,
    "connectionCount": 0,
    "terminalCount": 0
  }
}
```

Recommended client strategy:

- On startup, call `/api/v1/account/me` with the saved token.
- If the saved token is accepted, schedule heartbeat.
- Refresh when `expiresAt` is less than 30 minutes away.
- Also heartbeat every 15 minutes while the app is active.
- Replace the stored token atomically after heartbeat succeeds.
- If heartbeat returns `401`, clear the token and show the login screen.
- If the app is offline, keep the current token and retry when network returns.

The server does not revoke the old token during heartbeat. This avoids breaking
in-flight requests. The old token expires naturally.

## Logout

```http
POST /api/v1/account/logout
Authorization: Bearer <token>
```

Response:

```http
200 OK
```

Client behavior:

- Call logout when the user explicitly signs out.
- Clear the stored token and cached account after a successful logout.
- If logout fails because the network is offline, still clear the local token.

## Error Handling

Authentication errors use JSON:

```json
{
  "error": "unauthorized",
  "message": "Authentication required or token invalid"
}
```

Client handling:

- `401`: token missing, expired, revoked, or invalid. Clear token and request login.
- `403`: token is valid but the account lacks permission. Keep token, show an access denied message.
- `404` on `/me` or `/heartbeat`: account no longer exists. Clear token and request login.

## Minimal Client Flow

```mermaid
flowchart TD
  A["App starts"] --> B{"Saved token exists?"}
  B -- "No" --> C["Show login"]
  B -- "Yes" --> D["GET /api/v1/account/me"]
  D -- "200" --> E["Enter app and schedule heartbeat"]
  D -- "401/404" --> C
  C --> F["POST /api/v1/account/login"]
  F -- "200" --> E
  E --> G{"Token near expiry or 15 min elapsed?"}
  G -- "Yes" --> H["POST /api/v1/account/heartbeat"]
  H -- "200" --> I["Store fresh token"]
  H -- "401/404" --> C
  G -- "No" --> E
  I --> E
```

