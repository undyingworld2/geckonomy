# application.service

`EconomyService` — the facade of `suspend` functions that every adapter (commands, Vault providers)
calls. Delegates to the use cases; holds no business logic itself.

Arrives with **M4**.
