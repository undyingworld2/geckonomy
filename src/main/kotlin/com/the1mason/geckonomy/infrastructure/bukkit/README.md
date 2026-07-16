# infrastructure.bukkit

`command/*`, `listener/PlayerConnectionListener`, `BukkitMainThread`.

Commands launch a coroutine on the plugin scope, await the use case off the main thread, then hop back
via `BukkitMainThread` before touching any player or world. `PlayerConnectionListener` hydrates the
online balance mirror on join and evicts on quit.

Commands and permissions are specced in `docs/SPEC.md`; tested with MockBukkit, no real server needed.
Arrives with **M7**.
