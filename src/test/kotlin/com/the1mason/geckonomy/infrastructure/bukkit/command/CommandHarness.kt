package com.the1mason.geckonomy.infrastructure.bukkit.command

import com.the1mason.geckonomy.application.EconomyFixture
import com.the1mason.geckonomy.infrastructure.bukkit.CurrencyAccess
import com.the1mason.geckonomy.infrastructure.bukkit.MainThread
import com.the1mason.geckonomy.infrastructure.bukkit.PlayerTargets
import com.the1mason.geckonomy.infrastructure.config.ConfigService
import com.the1mason.geckonomy.infrastructure.config.StartOutcome
import com.the1mason.geckonomy.infrastructure.i18n.ErrorMessages
import com.the1mason.geckonomy.infrastructure.i18n.LanguageRepository
import com.the1mason.geckonomy.infrastructure.i18n.LogCapture
import com.the1mason.geckonomy.infrastructure.i18n.MessageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import org.mockbukkit.mockbukkit.entity.PlayerMock
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Logger
import kotlin.io.path.writeText

/**
 * A running plugin with Geckonomy's real commands over [EconomyFixture]'s in-memory ports.
 *
 * Commands are driven the way a player drives them — `dispatch(player, "balance")` — and asserted on
 * what arrives in chat, because that is the whole of what a command is. The layers underneath have
 * their own suites; what is untestable without a server is exactly this wiring: argument parsing, the
 * permission nodes, the alias, and which message comes back.
 *
 * **Three MockBukkit rules encoded here**, each of which costs an afternoon to rediscover:
 *
 * 1. Commands must register inside `onEnable` — Paper's `allowsLifecycleRegistration` flips false the
 *    moment enable returns, and a later call throws `Cannot register lifecycle event handlers`.
 * 2. Brigadier commands are invisible to `ServerMock.execute`, which reads the legacy command map and
 *    NPEs on a miss. [dispatch] goes through `dispatchCommand`.
 * 3. [TestPlugin] is `open` because MockBukkit subclasses it with ByteBuddy.
 *
 * Everything is synchronous: [Dispatchers.Unconfined] runs a `launch` on the calling thread until it
 * genuinely suspends, and the in-memory ports never do — so a dispatched command has finished replying
 * by the time [dispatch] returns. No ticks to pump.
 *
 * **Field order is load-bearing.** [plugin] enables during construction and reads everything above it,
 * so anything it touches must be declared first.
 */
internal class CommandHarness(val fixture: EconomyFixture = EconomyFixture()) {

    val server: ServerMock = MockBukkit.mock()

    val economy = fixture.service
    val currencies = fixture.currencies

    /** `settings.baltop-size`. A `var` so a test can shrink it and watch the limit apply. */
    var baltopSize: Int = 10

    /**
     * How `/baltop` reads [baltopSize] — the real command takes a supplier, because the setting is
     * reloadable. Overridable so a test can make the *read* throw: that is what a bug inside a command
     * coroutine actually looks like, and the alternative is a fake command class that proves nothing
     * about the real wiring.
     */
    var baltopSizeSupplier: () -> Int = { baltopSize }

    /** What the commands logged, for the tests that treat a log line as the behaviour under test. */
    val log = LogCapture()

    val messages: MessageService = MessageService(
        // An empty directory: LanguageRepository falls through to the en.yml bundled in the jar, which
        // is the real shipped file (LOCALIZATION.md §1). These tests assert real message text.
        LanguageRepository(Files.createTempDirectory("geckonomy-lang"), Logger.getAnonymousLogger()),
        language = { "en" },
    ).apply { reload() }

    /** The file [config] reads, so a test can corrupt it and watch a reload be rejected. */
    val configFile: Path = Files.createTempDirectory("geckonomy-config").resolve("config.yml").apply {
        writeText(defaultConfigYaml())
    }

    /**
     * A real [ConfigService] over [configFile] — only `/geckonomy reload` uses it.
     *
     * Its currency registry is deliberately *not* the one the commands use: the fakes are keyed by
     * [com.the1mason.geckonomy.domain.TestCurrencies]' objects, and a reload test asserts the reply,
     * not the reload's effect on balances.
     */
    val config: ConfigService = (ConfigService.start(configFile) as StartOutcome.Started).service

    val plugin: TestPlugin = MockBukkit.load(TestPlugin::class.java, this)

    /** Runs [line] as [who]. `false` when Bukkit found no such command. */
    fun dispatch(who: Player, line: String): Boolean = server.dispatchCommand(who, line)

    /** A player holding every base node, which is what `paper-plugin.yml` grants a real one. */
    fun player(name: String = "Alice"): PlayerMock = server.addPlayer(name).apply {
        addAttachment(plugin, "geckonomy.balance", true)
        addAttachment(plugin, "geckonomy.balance.others", true)
        addAttachment(plugin, "geckonomy.pay", true)
        addAttachment(plugin, "geckonomy.baltop", true)
    }

    /** A player with no Geckonomy permissions at all. */
    fun strangerWithNoPermissions(name: String = "Nobody"): PlayerMock = server.addPlayer(name)

    /** The next message [who] received, as plain text, or `null` if they got none. */
    fun nextMessage(who: PlayerMock): String? = who.nextComponentMessage()?.let(PLAIN::serialize)

    /** Every message [who] has received and not yet been asked about. */
    fun messages(who: PlayerMock): List<String> = generateSequence { nextMessage(who) }.toList()

    fun unmock() = MockBukkit.unmock()

    /**
     * Registers the real commands in `onEnable`, the only window Paper allows.
     *
     * Takes the harness as a constructor parameter — `MockBukkit.load` forwards them — so the fixture
     * is injected rather than reached for through a static.
     */
    open class TestPlugin(private val harness: CommandHarness) : JavaPlugin() {

        override fun onEnable() {
            val fixture = harness.fixture
            val access = CurrencyAccess(harness.currencies)
            val targets = PlayerTargets(server, harness.economy)
            val main = MainThread { it() }
            val errors = ErrorMessages(harness.messages, fixture.format)
            val replies = CommandReplies(harness.messages, errors, main)
            val permissions = GeckonomyPermissions(server.pluginManager, harness.currencies).apply { register() }

            GeckonomyCommands(
                plugin = this,
                scope = CoroutineScope(Dispatchers.Unconfined),
                currencies = harness.currencies,
                access = access,
                targets = targets,
                replies = replies,
                balance = BalanceCommand(harness.economy, access, targets, replies, fixture.format),
                pay = PayCommand(
                    harness.economy, access, targets, replies, harness.messages, fixture.format, main, server,
                ),
                baltop = BaltopCommand(
                    harness.economy, access, replies, harness.messages, fixture.format, main,
                    size = { harness.baltopSizeSupplier() },
                ),
                eco = EcoCommand(harness.economy, targets, replies, fixture.format),
                geckonomy = GeckonomyCommand(
                    harness.config, harness.messages, replies, permissions, logger, VERSION,
                ),
                logger = harness.log.logger,
            ).register()
        }
    }

    companion object {
        const val VERSION = "1.0-TEST"

        private val PLAIN: PlainTextComponentSerializer = PlainTextComponentSerializer.plainText()

        /** The shipped default config, which is what a real server reloads. */
        fun defaultConfigYaml(): String =
            CommandHarness::class.java.getResourceAsStream("/config.yml")!!.reader().readText()
    }
}
