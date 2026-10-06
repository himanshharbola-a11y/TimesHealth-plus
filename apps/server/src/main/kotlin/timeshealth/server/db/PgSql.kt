package timeshealth.server.db

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The Postgres operations the Node server relies on for correctness, as native SQL. JPA has no
 * faithful equivalent for these, so routes ported from Prisma use them directly. All of them
 * must run inside a transaction the caller opened (`@Transactional`); the JdbcClient shares the
 * JPA transaction's connection.
 *
 * Identifiers: Prisma's quoted names ("User", "firebaseUid"); "Order" must always be quoted.
 *
 * | Prisma / Node                                          | Here                              |
 * |--------------------------------------------------------|-----------------------------------|
 * | ``tx.$queryRaw`SELECT id FROM "LiveWorkshop" WHERE id = ${id} FOR UPDATE` `` | [lockRow]  |
 * | `tx.order.updateMany({ where: { id, status: 'PENDING' }, data })` used as a claim (count === 1) | [claim] |
 * | `createMany({ data, skipDuplicates: true })` / create + catch P2002 | [insertIgnoringConflict] |
 * | ``tx.$executeRaw`SELECT pg_advisory_xact_lock(${key})` `` | [advisoryXactLock]         |
 */
@Component
class PgSql(val jdbc: JdbcClient) {

    /**
     * `SELECT 1 FROM "<table>" WHERE id = ? FOR UPDATE`: holds the row lock until the transaction
     * ends, serialising concurrent writers (workshop capacity, orders.ts). Returns false when the
     * row does not exist.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun lockRow(table: String, id: String, idColumn: String = "id"): Boolean =
        jdbc.sql("SELECT 1 FROM ${quote(table)} WHERE ${quote(idColumn)} = :id FOR UPDATE")
            .param("id", id)
            .query(Int::class.java)
            .optional()
            .isPresent

    /**
     * An atomic conditional UPDATE used as a claim: exactly one concurrent caller sees `true`.
     * Pass the full statement with named parameters, e.g.
     * ```
     * claim("""UPDATE "Order" SET status = 'PAID', "updatedAt" = now()
     *          WHERE id = :id AND status IN ('CREATED','PENDING')""", mapOf("id" to orderId))
     * ```
     * Remember `"updatedAt"`: Prisma's updateMany sets @updatedAt columns, raw SQL does not.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun claim(updateSql: String, params: Map<String, Any?>): Boolean = update(updateSql, params) == 1

    /** Rows affected by an UPDATE/DELETE (Prisma's `{ count }`). */
    @Transactional(propagation = Propagation.MANDATORY)
    fun update(sql: String, params: Map<String, Any?> = emptyMap()): Int =
        jdbc.sql(sql).params(params).update()

    /**
     * `INSERT … ON CONFLICT DO NOTHING` (Prisma `skipDuplicates`). [columns] maps quoted-as-needed
     * column names to values. Returns true when a row was inserted, false when it already existed.
     * Supply `"id" to Cuid.next()` for cuid-keyed tables, and every NOT NULL column without a
     * database default (e.g. "updatedAt").
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun insertIgnoringConflict(table: String, columns: Map<String, Any?>, conflictTarget: List<String>? = null): Boolean {
        val names = columns.keys.toList()
        val target = conflictTarget?.joinToString(", ", prefix = "(", postfix = ")") { quote(it) } ?: ""
        val sql = "INSERT INTO ${quote(table)} (${names.joinToString(", ") { quote(it) }}) " +
            "VALUES (${names.indices.joinToString(", ") { ":p$it" }}) ON CONFLICT $target DO NOTHING"
        val params = names.indices.associate { "p$it" to toJdbc(columns.getValue(names[it])) }
        return jdbc.sql(sql).params(params).update() == 1
    }

    /**
     * `pg_advisory_xact_lock(key)`: a transaction-scoped mutex across every server instance
     * (services/push.ts serialises notification claims with it). Released at commit/rollback.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun advisoryXactLock(key: Long) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", key).query().singleRow()
    }

    companion object {
        /** Quotes an identifier the way Prisma names it ("User", "firebaseUid"). */
        fun quote(identifier: String): String {
            require(identifier.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Bad SQL identifier: $identifier" }
            return "\"$identifier\""
        }

        /** java.time values bound as JDBC types Postgres maps to TIMESTAMP(3) / DATE. */
        private fun toJdbc(v: Any?): Any? = when (v) {
            is java.time.Instant -> java.sql.Timestamp.from(v)
            is java.time.LocalDate -> java.sql.Date.valueOf(v)
            is List<*> -> v.map { it?.toString() }.toTypedArray()
            else -> v
        }
    }
}
