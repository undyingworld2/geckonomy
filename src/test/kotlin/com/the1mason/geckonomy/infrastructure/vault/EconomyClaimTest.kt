package com.the1mason.geckonomy.infrastructure.vault

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.RegisteredServiceProvider
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.ServicesManager
import org.junit.jupiter.api.Test
import java.util.logging.Level
import java.util.logging.Logger
import net.milkbowl.vault.economy.Economy as LegacyVaultEconomy
import net.milkbowl.vault2.economy.Economy as VaultUnlockedEconomy

/**
 * The economy takeover (`settings.claim-vault-economy`).
 *
 * Mocks the `ServicesManager` rather than standing up MockBukkit: the logic under test is entirely
 * "given these registrations, unregister the right ones", and a mocked manager states exactly that.
 */
class EconomyClaimTest {

    private val servicesManager = mockk<ServicesManager>(relaxed = true)
    private val pluginManager = mockk<PluginManager>(relaxed = true)
    private val server = mockk<Server> {
        every { servicesManager } returns this@EconomyClaimTest.servicesManager
        every { pluginManager } returns this@EconomyClaimTest.pluginManager
    }
    private val ours = plugin("Geckonomy")
    private val silent: Logger = Logger.getAnonymousLogger().apply { level = Level.OFF }

    private var claimEnabled = true
    private val claim = EconomyClaim(ours, { claimEnabled }, silent)

    private fun plugin(name: String): Plugin = mockk {
        every { this@mockk.name } returns name
        every { this@mockk.server } returns this@EconomyClaimTest.server
    }

    private val legacy = LegacyVaultEconomy::class.java
    private val unlocked = VaultUnlockedEconomy::class.java

    private fun <T : Any> registration(service: Class<T>, provider: T, plugin: Plugin) =
        RegisteredServiceProvider(service, provider, ServicePriority.Normal, plugin)

    @Test
    fun `sweep unregisters a third-party economy provider`() {
        val ess = registration(legacy, mockk<LegacyVaultEconomy>(relaxed = true), plugin("Essentials"))
        every { servicesManager.getRegistrations(legacy) } returns listOf(ess)
        every { servicesManager.getRegistrations(unlocked) } returns emptyList()

        claim.sweep()

        verify { servicesManager.unregister(legacy, ess.provider) }
    }

    @Test
    fun `sweep covers both the v1 and v2 economy services`() {
        val essV2 = registration(unlocked, mockk<VaultUnlockedEconomy>(relaxed = true), plugin("Essentials"))
        every { servicesManager.getRegistrations(legacy) } returns emptyList()
        every { servicesManager.getRegistrations(unlocked) } returns listOf(essV2)

        claim.sweep()

        verify { servicesManager.unregister(unlocked, essV2.provider) }
    }

    @Test
    fun `sweep never touches Geckonomy's own providers`() {
        val mine = registration(legacy, mockk<LegacyVaultEconomy>(relaxed = true), ours)
        every { servicesManager.getRegistrations(legacy) } returns listOf(mine)
        every { servicesManager.getRegistrations(unlocked) } returns emptyList()

        claim.sweep()

        verify(exactly = 0) { servicesManager.unregister(any<Class<*>>(), any()) }
    }

    @Test
    fun `sweep never touches Vault's own bridge providers`() {
        // VaultUnlocked (plugin name "Vault") may register bridges; ours already outrank them.
        val bridge = registration(legacy, mockk<LegacyVaultEconomy>(relaxed = true), plugin("Vault"))
        every { servicesManager.getRegistrations(legacy) } returns listOf(bridge)
        every { servicesManager.getRegistrations(unlocked) } returns emptyList()

        claim.sweep()

        verify(exactly = 0) { servicesManager.unregister(any<Class<*>>(), any()) }
    }

    @Test
    fun `sweep does nothing when the toggle is off`() {
        claimEnabled = false

        claim.sweep()

        verify(exactly = 0) { servicesManager.getRegistrations(any<Class<*>>()) }
        verify(exactly = 0) { servicesManager.unregister(any<Class<*>>(), any()) }
    }

    @Test
    fun `a competitor registering later is unregistered by the listener`() {
        val ess = registration(legacy, mockk<LegacyVaultEconomy>(relaxed = true), plugin("Essentials"))

        claim.suppressIfCompetitor(ess)

        verify { servicesManager.unregister(legacy, ess.provider) }
    }

    @Test
    fun `the listener ignores a non-economy service`() {
        val other = registration(Runnable::class.java, mockk<Runnable>(relaxed = true), plugin("SomePlugin"))

        claim.suppressIfCompetitor(other)

        verify(exactly = 0) { servicesManager.unregister(any<Class<*>>(), any()) }
    }

    @Test
    fun `the listener ignores our own registration`() {
        val mine = registration(unlocked, mockk<VaultUnlockedEconomy>(relaxed = true), ours)

        claim.suppressIfCompetitor(mine)

        verify(exactly = 0) { servicesManager.unregister(any<Class<*>>(), any()) }
    }

    @Test
    fun `the listener stands down when the toggle is off`() {
        claimEnabled = false
        val ess = registration(legacy, mockk<LegacyVaultEconomy>(relaxed = true), plugin("Essentials"))

        claim.suppressIfCompetitor(ess)

        verify(exactly = 0) { servicesManager.unregister(any<Class<*>>(), any()) }
    }
}
