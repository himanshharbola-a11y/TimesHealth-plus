package timeshealth.server.json

import java.lang.reflect.Type
import java.nio.charset.StandardCharsets
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.springframework.core.ResolvableType
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.AbstractGenericHttpMessageConverter
import org.springframework.http.converter.HttpMessageNotReadableException

/**
 * Spring MVC ⇄ kotlinx.serialization, for `@Serializable` types (the shared Android contract in
 * timeshealth.app.core.model, and server-local DTOs) and raw [JsonElement]s. Registered first, so
 * those types never reach Jackson. Everything else (String bodies, framework types) falls through
 * to Spring's default converters.
 *
 * Writes with [ServerJson] (`explicitNulls = true`, see there for why).
 */
class KotlinxJsonHttpMessageConverter :
    AbstractGenericHttpMessageConverter<Any>(MediaType.APPLICATION_JSON, MediaType("application", "*+json")) {

    init {
        defaultCharset = StandardCharsets.UTF_8
    }

    override fun supports(clazz: Class<*>): Boolean = isSerializableClass(clazz)

    override fun canRead(type: Type, contextClass: Class<*>?, mediaType: MediaType?): Boolean =
        isSerializableClass(ResolvableType.forType(type).toClass()) && canRead(mediaType)

    override fun canWrite(type: Type?, clazz: Class<*>, mediaType: MediaType?): Boolean {
        val raw = if (type != null) ResolvableType.forType(type).toClass() else clazz
        return isSerializableClass(raw) && canWrite(mediaType)
    }

    override fun readInternal(clazz: Class<*>, inputMessage: HttpInputMessage): Any =
        decode(clazz, inputMessage)

    override fun read(type: Type, contextClass: Class<*>?, inputMessage: HttpInputMessage): Any =
        decode(type, inputMessage)

    private fun decode(type: Type, inputMessage: HttpInputMessage): Any {
        val text = inputMessage.body.readAllBytes().toString(StandardCharsets.UTF_8)
        return try {
            ServerJson.decodeFromString(serializerFor(type), text)
        } catch (e: SerializationException) {
            throw HttpMessageNotReadableException("Invalid JSON: ${e.message}", e, inputMessage)
        } catch (e: IllegalArgumentException) {
            throw HttpMessageNotReadableException("Invalid JSON: ${e.message}", e, inputMessage)
        }
    }

    override fun writeInternal(value: Any, type: Type?, outputMessage: HttpOutputMessage) {
        val serializer = serializerFor(type ?: value.javaClass)
        val bytes = ServerJson.encodeToString(serializer, value).toByteArray(StandardCharsets.UTF_8)
        outputMessage.headers.contentLength = bytes.size.toLong()
        outputMessage.body.write(bytes)
    }

    @Suppress("UNCHECKED_CAST")
    private fun serializerFor(type: Type): KSerializer<Any> =
        ServerJson.serializersModule.serializer(type) as KSerializer<Any>

    companion object {
        fun isSerializableClass(clazz: Class<*>): Boolean =
            JsonElement::class.java.isAssignableFrom(clazz) ||
                clazz.isAnnotationPresent(kotlinx.serialization.Serializable::class.java)
    }
}
