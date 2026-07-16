package com.the1mason.geckonomy.infrastructure.persistence

import java.sql.Connection
import javax.sql.DataSource

/**
 * Where a repository gets its connection.
 *
 * The seam that lets one repository serve two lifetimes. Outside a transaction, every call is its own
 * connection and its own implicit commit; inside [SqlUnitOfWork.transaction], every call must ride the
 * *same* connection or it commits independently — which for a transfer would mean debiting without
 * crediting. Rather than write each repository twice, they take a [ConnectionSource] and the unit of
 * work swaps [Pooled] for [Pinned].
 */
interface ConnectionSource {

    /**
     * Runs [block] with a connection.
     *
     * Whether the connection is closed afterwards, and whether the work commits on its own, is the
     * implementation's business — which is precisely what the caller must not care about.
     */
    fun <T> use(block: (Connection) -> T): T

    /** A connection per call, borrowed from the pool and returned after. Autocommit; no transaction. */
    class Pooled(private val dataSource: DataSource) : ConnectionSource {
        override fun <T> use(block: (Connection) -> T): T = dataSource.connection.use(block)
    }

    /**
     * One connection, handed to every call, never closed here.
     *
     * Owned by the [SqlUnitOfWork] that opened the transaction and closed by it on commit or
     * rollback. Not thread-safe, and does not need to be: the unit of work runs its block on the
     * single IO context it started on, so calls through it are sequential.
     */
    class Pinned(private val connection: Connection) : ConnectionSource {
        override fun <T> use(block: (Connection) -> T): T = block(connection)
    }
}
