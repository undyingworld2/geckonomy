# infrastructure.bukkit

Everything that talks to the server: the join listener, the commands, and the two rules they share.

`PlayerConnectionListener` creates the account (idempotent, seeds starting balances per FR-A6) and
hydrates the online balance mirror on **`AsyncPlayerPreLoginEvent`**, not `PlayerJoinEvent`: that event
already runs off the main thread, so the read is free of NFR-1, and it completes before the player
exists to anyone else — which closes the race where a plugin asks for a balance that is still loading
and gets served by the blocking fallback. It evicts on quit, and on a login refused after pre-login
allowed it (no quit event follows a player who never joined, so the entry would leak).

A database failure at join warns but does not refuse the login: the sync path falls back to reading the
database directly until it recovers, and refusing entry over a sick economy is the worse outage.

## Commands

`command/GeckonomyCommands` is the **shell and only the shell** — it registers the Brigadier nodes,
parses arguments, checks the base permission, and hands over. Each command's rules live in its own class
(`BalanceCommand`, `PayCommand`, …), which knows nothing about Brigadier — the same split that keeps the
Vault providers thin over `VaultSyncPath`. A handler launches on the plugin scope, awaits the use case
off the main thread, then replies through `CommandReplies`, which hops back via `BukkitMainThread`.

**Paper Brigadier, not Cloud v2** — reverted on evidence at M7; `docs/tasks/M7-commands.md` carries the
reasons and the three MockBukkit rules that come with it.

`/balance <word>` is ambiguous by construction: the word may be a currency or a player, and two `word()`
arguments cannot disambiguate by content — Brigadier matches the first child greedily — so the handler
decides.

`CurrencyAccess` answers "may this player use this currency for this action", and is the *single* source
for both the handlers and tab completion: offering a currency that is then refused is a bug the player
sees, and one class forecloses it. It resolves two independent gates — the config flags
(`transferable`, `balance-check-others`, `show-in-baltop`), which no permission grants past, and the
per-currency nodes.

`GeckonomyPermissions` registers those nodes, which are named after config and so cannot live in
`paper-plugin.yml` beside the base ones. An unregistered node defaults to **op**: without this, every
per-currency check would refuse every normal player while working perfectly for the admin testing it.

`PlayerTargets` resolves a typed name to an account. `serverLookup` holds the one rule it shares with
`vault.PlayerResolver`: **never `Server.getOfflinePlayer(String)`**, which may block on a Mojang round
trip and invents a player for names that were never real.
