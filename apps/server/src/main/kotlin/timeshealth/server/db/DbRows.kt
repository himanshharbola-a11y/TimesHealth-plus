package timeshealth.server.db

import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/*
 * Row-reading helpers for JdbcClient queries. Prisma stores DateTime as TIMESTAMP(3) WITHOUT time
 * zone holding UTC wall-clock time, so timestamps are read and written as UTC LocalDateTime
 * explicitly — never through the JVM's default zone.
 */

fun ResultSet.instant(column: String): Instant? =
    getObject(column, LocalDateTime::class.java)?.toInstant(ZoneOffset.UTC)

fun ResultSet.requireInstant(column: String): Instant =
    instant(column) ?: error("$column is null")

fun ResultSet.stringOrNull(column: String): String? = getString(column)

fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

fun ResultSet.stringList(column: String): List<String> =
    (getArray(column)?.array as? Array<*>)?.map { it.toString() } ?: emptyList()

/** For binding a TIMESTAMP(3) parameter. */
fun Instant.toDbTime(): LocalDateTime = LocalDateTime.ofInstant(this, ZoneOffset.UTC)
