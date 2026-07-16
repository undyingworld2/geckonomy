package com.the1mason.geckonomy.application.result

/**
 * The result of an economy operation: what it produced, or why it failed.
 *
 * One generic type rather than a sealed pair per operation. The use cases answer with six different
 * shapes — a balance, two balances, a boolean, a name, a name map, nothing — and modelling each as
 * its own `Success`/`Failure` hierarchy would be six near-identical copies. Decisively, it is what
 * lets the storage-exception boundary be *stated once*
 * ([com.the1mason.geckonomy.application.usecase.StorageGuard]): a guard that wraps every port call
 * can only exist if there is one shape for it to return.
 *
 * Sealed, so an adapter's `when` is exhaustive and adding an [EconomyError] variant fails the build
 * (`application/result/README.md`). The names the docs fix — `OperationResult`, `TransferResult` —
 * survive as typealiases over this type.
 */
sealed interface Outcome<out T> {

    /** The operation happened, and produced [value]. */
    data class Success<out T>(val value: T) : Outcome<T>

    /**
     * The operation did not happen, because of [error].
     *
     * `Outcome<Nothing>`, so a failure is assignable to an `Outcome<T>` of any `T` — which is what
     * lets [then] pass one along without rebuilding it.
     */
    data class Failure(val error: EconomyError) : Outcome<Nothing>
}

/**
 * Continues with [block] if this succeeded, short-circuiting a failure unchanged.
 *
 * Keeps a use case that validates a currency, then an amount, then touches storage reading as three
 * steps instead of a `when` pyramid three levels deep.
 *
 * `inline`, so [block] may suspend — every step it chains is a port call.
 */
inline fun <T, R> Outcome<T>.then(block: (T) -> Outcome<R>): Outcome<R> = when (this) {
    is Outcome.Success -> block(value)
    is Outcome.Failure -> this
}

/** Transforms a success's value, leaving a failure alone. [then] for a step that cannot itself fail. */
inline fun <T, R> Outcome<T>.map(block: (T) -> R): Outcome<R> = then { Outcome.Success(block(it)) }
