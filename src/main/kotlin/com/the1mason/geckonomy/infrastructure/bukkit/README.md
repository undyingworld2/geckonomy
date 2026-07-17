# infrastructure.bukkit

`listener/PlayerConnectionListener`; `command/*` and `BukkitMainThread` are not written yet.

`PlayerConnectionListener` creates the account (idempotent, seeds starting balances per FR-A6) and
hydrates the online balance mirror on **`AsyncPlayerPreLoginEvent`**, not `PlayerJoinEvent`: that event
already runs off the main thread, so the read is free of NFR-1, and it completes before the player
exists to anyone else — which closes the race where a plugin asks for a balance that is still loading
and gets served by the blocking fallback. It evicts on quit, and on a login refused after pre-login
allowed it (no quit event follows a player who never joined, so the entry would leak).

A database failure at join warns but does not refuse the login: the sync path falls back to reading the
database directly until it recovers, and refusing entry over a sick economy is the worse outage.

Commands will launch a coroutine on the plugin scope, await the use case off the main thread, then hop
back via `BukkitMainThread` before touching any player or world. Commands and permissions are specced
in `docs/SPEC.md`; tested with MockBukkit, no real server needed.
