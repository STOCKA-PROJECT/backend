# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Stocka Backend — a Spring Boot 4.0.6 REST API (Java 25) providing JWT-based authentication and role-based authorization. Uses MariaDB 11 for persistence and Maven for builds.

## Build & Run Commands

```bash
# Start MariaDB (required before running the app)
docker compose up -d

# Build
./mvnw clean package

# Build (skip tests)
./mvnw clean package -DskipTests

# Run in dev mode (with live reload via DevTools)
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=BackendApplicationTests
```

The app runs on port **9095**. MariaDB is exposed on host port **3307**.

## Architecture

Module-based layout under `src/main/java/com/stocka/backend/modules/`:

```
modules/
├── auth/          Controller + service for signup/login
├── bootstrap/     RoleSeeder (order=1) and AdminSeeder (order=2) run on startup
├── health/        GET /health (public)
├── roles/         Role entity (USER, ADMIN) + repository
├── security/      SecurityConfiguration, JwtAuthenticationFilter, JwtService
└── users/         User entity, UserController, AdminController, UserService
```

Each module follows **Controller → Service → Repository** layering with constructor-based DI.

### Authentication & Security

- Stateless JWT auth (HMAC-SHA256, 24h expiration, JJWT library)
- `JwtAuthenticationFilter` extracts Bearer token, validates, and sets `SecurityContext`
- BCrypt password encoding
- Public endpoints: `/auth/**`, `/health`. Everything else requires authentication.
- Role-based access via `@PreAuthorize` annotations (e.g. `hasRole('ADMIN')`)
- User entity implements `UserDetails`; email is used as the Spring Security username

### Database

- MariaDB 11 via Docker Compose (container: `stocka-bd`, port 3307→3306)
- Spring Data JPA with `ddl-auto=update` (auto schema migration)
- H2 available as test-scoped dependency
- Entities: `User` (ManyToOne → `Role`), `Role` (enum: USER, ADMIN)

### Bootstrap Seeders

On startup, `RoleSeeder` creates USER/ADMIN roles, then `AdminSeeder` creates a default admin account. Both are idempotent (skip if data exists).

## API Endpoints

| Method | Path           | Access        | Description              |
|--------|----------------|---------------|--------------------------|
| GET    | `/health`      | Public        | Health check             |
| POST   | `/auth/signup` | Public        | Register user            |
| POST   | `/auth/login`  | Public        | Login, returns JWT       |
| GET    | `/users/me`    | Authenticated | Current user profile     |
| GET    | `/users`       | ADMIN         | List all users           |
| POST   | `/admins`      | ADMIN         | Create admin user        |

## API Testing

Bruno collections are in `bruno/`. See `bruno/README.md` for the recommended test sequence.

---

## Core Purpose

The main objective of this backend is to allow users to manage **organizations** and their associated **assets ("pieces")** in a structured and flexible way.

Each user belongs to one or more organizations and can manage the pieces within those organizations depending on their permissions.

---

## Key Features

### 1. Authentication & User Management

* User registration and login
* Authentication (JWT-based or session-based)
* Role-based access control (RBAC)
* Association of users with organizations

### 2. Organization Management

* Create, update, and delete organizations
* Assign users to organizations
* Manage organization-level permissions

### 3. Piece Management (Core Domain)

Each **piece** represents an asset belonging to an organization.

A piece includes:

* Basic attributes (name, description, etc.)
* Custom attributes based on its type
* Associated images and files
* Ownership information
* Location within a hierarchical structure

### 4. Piece Types

* Define reusable **piece types**
* Each type can have its own schema of attributes
* Pieces inherit structure from their type

### 5. File & Image Storage

* All media (images and files) are stored in **Cloudflare R2**
* Backend handles upload, retrieval, and deletion
* References to files are stored in the database

### 6. Ownership

* Each piece can have one or more **owners**
* Ownership can be used for filtering, permissions, or tracking

### 7. Location System (Hierarchical)

* Locations are structured as a **tree**
* A location can contain:

  * Sub-locations
  * Pieces
* Enables flexible organization (e.g., warehouse → shelf → box)

---

## Storage

* Cloudflare R2 for object storage (images/files)

---

## Design Principles

* **Modularity**: Clear separation between domains (users, organizations, pieces, locations, etc.)
* **Scalability**: Designed to support growing data and users
* **Extensibility**: Piece types and attributes allow flexible data modeling
* **Security**: Authentication and authorization enforced across all endpoints
* **Maintainability**: Clean architecture and consistent coding practices

---

## Future Considerations

* Audit logs for tracking changes
* Advanced permissions system
* Search and filtering capabilities
* Pagination and performance optimizations
* Caching layer (e.g., Redis)
* Event-driven features (webhooks, notifications)

---

## Open Questions

(To be defined as the project evolves)

* What authentication strategy will be used (JWT, OAuth, etc.)?
* Will users belong to multiple organizations simultaneously?
* What level of granularity is required for permissions?
* How complex should piece-type attribute schemas be?
* Should location trees support reordering and drag-and-drop semantics?

---

## Summary

This backend serves as the central system for managing organizational assets in a structured, extensible way. It integrates relational data (MariaDB), object storage (Cloudflare R2), and a modern Java backend (Spring Boot), all packaged in a Dockerized environment for easy deployment.
