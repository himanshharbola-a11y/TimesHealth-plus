package timeshealth.server.db

import kotlinx.serialization.json.JsonElement
import org.hibernate.type.descriptor.WrapperOptions
import org.hibernate.type.descriptor.java.JavaType
import org.hibernate.type.format.FormatMapper
import timeshealth.server.json.ServerJson

/**
 * Hibernate's JSON (jsonb) column mapper, on kotlinx.serialization instead of Jackson, so Jackson
 * never touches API data (configured as hibernate.type.json_format_mapper in application.yml).
 *
 * Entities keep jsonb as its JSON text (a `String` attribute with `@JdbcTypeCode(SqlTypes.JSON)`)
 * and expose a [JsonElement] view; see PromoCampaignEntity.action and RaceResultEntity.splits.
 * Postgres normalises jsonb (it reorders keys), and the text read back is what Prisma returns too.
 */
class KotlinxJsonFormatMapper : FormatMapper {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> fromString(charSequence: CharSequence, javaType: JavaType<T>, wrapperOptions: WrapperOptions): T {
        val text = charSequence.toString()
        val type = javaType.javaTypeClass
        return when {
            type == String::class.java || type == Any::class.java -> text as T
            JsonElement::class.java.isAssignableFrom(type) -> ServerJson.parseToJsonElement(text) as T
            else -> throw IllegalArgumentException("Unsupported JSON attribute type ${type.name}; map jsonb as String")
        }
    }

    override fun <T : Any?> toString(value: T, javaType: JavaType<T>, wrapperOptions: WrapperOptions): String =
        when (value) {
            null -> "null"
            is String -> value
            is JsonElement -> value.toString()
            else -> throw IllegalArgumentException("Unsupported JSON attribute type ${value!!::class.java.name}")
        }
}
