package com.the1mason.geckonomy.infrastructure.vault

import net.milkbowl.vault2.economy.Economy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal
import java.util.UUID

/**
 * Pins the `Economy` methods whose interface defaults are wrong for us.
 *
 * VaultUnlocked ships `default` bodies that a provider silently inherits by simply not writing the
 * method. Three of them are actively harmful here, and none of them fails to compile:
 * - `transfer` is a withdraw-then-deposit with a compensating refund — not atomic, against FR-B5;
 * - `set` reads the balance and then withdraws or deposits the difference — two writes, and racy;
 * - `canWithdraw`/`canDeposit` answer `NOT_IMPLEMENTED`, against FR-B4;
 * - `fractionalDigits(plugin, currency)` throws the currency away and answers for the default one.
 *
 * A regression here is a deletion, which no other test would notice: the code would still compile,
 * still return a plausible response, and quietly stop being atomic. Hence reflection.
 */
class VaultDefaultsTest {

    private val overridden = listOf<Triple<String, Array<Class<*>>, String>>(
        Triple("transfer", arrayOf(String::class.java, UUID::class.java, UUID::class.java, BigDecimal::class.java), "atomicity (FR-B5)"),
        Triple("transfer", arrayOf(String::class.java, UUID::class.java, UUID::class.java, String::class.java, BigDecimal::class.java), "atomicity (FR-B5)"),
        Triple("transfer", arrayOf(String::class.java, UUID::class.java, UUID::class.java, String::class.java, String::class.java, BigDecimal::class.java), "atomicity (FR-B5)"),
        Triple("set", arrayOf(String::class.java, UUID::class.java, BigDecimal::class.java), "one write, not two"),
        Triple("set", arrayOf(String::class.java, UUID::class.java, String::class.java, BigDecimal::class.java), "one write, not two"),
        Triple("set", arrayOf(String::class.java, UUID::class.java, String::class.java, String::class.java, BigDecimal::class.java), "one write, not two"),
        Triple("canWithdraw", arrayOf(String::class.java, UUID::class.java, BigDecimal::class.java), "FR-B4"),
        Triple("canWithdraw", arrayOf(String::class.java, UUID::class.java, String::class.java, BigDecimal::class.java), "FR-B4"),
        Triple("canWithdraw", arrayOf(String::class.java, UUID::class.java, String::class.java, String::class.java, BigDecimal::class.java), "FR-B4"),
        Triple("canDeposit", arrayOf(String::class.java, UUID::class.java, BigDecimal::class.java), "FR-B4"),
        Triple("canDeposit", arrayOf(String::class.java, UUID::class.java, String::class.java, BigDecimal::class.java), "FR-B4"),
        Triple("canDeposit", arrayOf(String::class.java, UUID::class.java, String::class.java, String::class.java, BigDecimal::class.java), "FR-B4"),
        Triple("fractionalDigits", arrayOf(String::class.java, String::class.java), "per-currency digits"),
        Triple("supportsAsync", emptyArray(), "FR-V2"),
        Triple("async", emptyArray(), "FR-V2"),
    )

    @TestFactory
    fun `the dangerous interface defaults are overridden`(): List<DynamicTest> =
        overridden.map { (name, parameters, why) ->
            DynamicTest.dynamicTest("$name(${parameters.size} args) — $why") {
                val method = VaultUnlockedEconomyProvider::class.java.getMethod(name, *parameters)

                assertEquals(
                    VaultUnlockedEconomyProvider::class.java,
                    method.declaringClass,
                    "$name inherits Vault's default body, which is wrong here: $why",
                )
            }
        }

    @TestFactory
    fun `every abstract Economy method is implemented`(): List<DynamicTest> =
        Economy::class.java.methods
            .filter { java.lang.reflect.Modifier.isAbstract(it.modifiers) }
            .map { abstract ->
                DynamicTest.dynamicTest("${abstract.name}(${abstract.parameterCount})") {
                    val method = VaultUnlockedEconomyProvider::class.java.getMethod(abstract.name, *abstract.parameterTypes)

                    assertEquals(VaultUnlockedEconomyProvider::class.java, method.declaringClass)
                }
            }
}
