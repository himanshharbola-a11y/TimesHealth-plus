package timeshealth.server.json

import jakarta.servlet.http.HttpServletRequest
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import timeshealth.server.error.HttpError

/**
 * A request body exactly as Fastify's default content-type parsers hand it to a Node route, so a
 * route can run the same zod-style validation on it (see validation/Zod.kt).
 *
 * [value] is null when there is no body at all (Node's `req.body === undefined`), a [JsonElement]
 * for `application/json` (which may be JSON `null`), and a [JsonPrimitive] string for
 * `text/plain`.
 *
 * Declare a `RawBody` parameter on every POST/PUT/PATCH/DELETE handler, even one that ignores
 * the body: Fastify parses (and can reject) a body on every such route.
 */
class RawBody(val value: JsonElement?)

/**
 * Fastify 5's body rules (lib/handleRequest.js, lib/contentTypeParser.js):
 *  - GET/HEAD: the body is never parsed.
 *  - no Content-Type and no body: nothing to parse.
 *  - Content-Type application/json: an empty body is 400 "Body cannot be empty when content-type
 *    is set to 'application/json'", invalid JSON is 400, a `__proto__`/`constructor.prototype`
 *    key is 400 (secure-json-parse).
 *  - text/plain: the raw string.
 *  - any other type (or a body with no type): 415 "Unsupported Media Type: <type>".
 *  - over 1 MiB (bodyLimit): 413 "Request body is too large".
 * All of these surface through the error handler as `BAD_REQUEST` (status < 500).
 */
class RawBodyArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == RawBody::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): RawBody {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)
            ?: return RawBody(null)
        return read(request)
    }

    companion object {
        const val BODY_LIMIT_BYTES = 1_048_576

        fun read(request: HttpServletRequest): RawBody {
            val method = request.method.uppercase()
            if (method == "GET" || method == "HEAD") return RawBody(null)

            val contentType = request.getHeader("Content-Type")
            val declaredLength = request.getHeader("Content-Length")
            val chunked = request.getHeader("Transfer-Encoding") != null
            if (contentType == null && !chunked && (declaredLength == null || declaredLength == "0")) {
                return RawBody(null)
            }

            val mediaType = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            val isJson = mediaType == "application/json"
            val isText = mediaType == "text/plain"
            if (!isJson && !isText) {
                throw HttpError(415, "Unsupported Media Type: ${contentType.orEmpty()}")
            }

            val bytes = request.inputStream.readNBytes(BODY_LIMIT_BYTES + 1)
            if (bytes.size > BODY_LIMIT_BYTES) throw HttpError(413, "Request body is too large")
            val text = String(bytes, StandardCharsets.UTF_8)

            if (isText) return RawBody(JsonPrimitive(text))
            if (text.isEmpty()) {
                throw HttpError(400, "Body cannot be empty when content-type is set to 'application/json'")
            }
            val element = try {
                ServerJson.parseToJsonElement(text)
            } catch (e: Exception) {
                throw HttpError(400, "Body is not valid JSON")
            }
            if (hasForbiddenPrototypeKey(element)) {
                throw HttpError(400, "Object contains forbidden prototype property")
            }
            return RawBody(element)
        }

        private fun hasForbiddenPrototypeKey(element: JsonElement): Boolean = when (element) {
            is JsonObject -> element.keys.contains("__proto__") ||
                (element["constructor"] as? JsonObject)?.containsKey("prototype") == true ||
                element.values.any(::hasForbiddenPrototypeKey)
            is JsonArray -> element.any(::hasForbiddenPrototypeKey)
            else -> false
        }
    }
}
