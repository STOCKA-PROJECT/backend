# Stocka Auth API (Bruno)

Orden recomendado para probar:

1. `stock-auth/00-public/hello`
2. `stock-auth/10-auth/signup-user`
3. `stock-auth/10-auth/login-user`
4. `stock-auth/30-admins/create-admin-owner`
5. `stock-auth/20-users/get-me-owner`
6. `stock-auth/20-users/get-all-users-owner`
7. `stock-auth/20-users/get-all-users-admin`
8. `stock-auth/30-admins/create-admin-admin-forbidden`

Notas:

- `owner@stocka.local / 123456` lo crea el `AdminSeeder` al arrancar la app.
- Si ejecutas dos veces `signup-user` o `create-admin-owner`, puede fallar por email duplicado (esperado).
