package timeshealth.server.error

import jakarta.servlet.RequestDispatcher
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.web.servlet.error.ErrorController
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import timeshealth.app.core.model.ApiError
import timeshealth.server.config.ServerEnv

/**
 * The app.ts `setErrorHandler` + `setNotFoundHandler`, for everything that reaches Spring MVC.
 *
 *  - [ApiException]: a deliberate reply (`reply.code(409).send({ code, message })`), verbatim.
 *  - [HttpError] and framework errors: code from the status (401 UNAUTHORIZED, 404 NOT_FOUND,
 *    429 TOO_MANY_REQUESTS, 503 UNAVAILABLE, other 5xx INTERNAL, other 4xx BAD_REQUEST); a 5xx
 *    message is "Something went wrong" in production.
 *  - Unknown route, and a known path with the wrong method: 404 `{ code: NOT_FOUND, message:
 *    "Not found" }`. Fastify has no 405; an unmatched method is simply not a route.
 *
 * Bodies are serialised by [ApiErrors] (nulls omitted, so `fields` only appears when set).
 */
@RestControllerAdvice
class ApiErrorAdvice(private val env: ServerEnv) {
    private val log = LoggerFactory.getLogger(ApiErrorAdvice::class.java)

    @ExceptionHandler(ApiException::class)
    fun deliberate(ex: ApiException): ResponseEntity<String> =
        respond(ex.status, ApiError(code = ex.code, message = ex.message, fields = ex.fields))

    @ExceptionHandler(HttpError::class)
    fun thrown(ex: HttpError): ResponseEntity<String> {
        if (ex.status >= 500) log.error("request failed", ex)
        return respond(ex.status, ApiErrors.forStatus(ex.status, ex.message, env.isProd))
    }

    @ExceptionHandler(
        NoHandlerFoundException::class,
        NoResourceFoundException::class,
        HttpRequestMethodNotSupportedException::class,
    )
    fun notFound(): ResponseEntity<String> = respond(404, ApiErrors.NOT_FOUND)

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(ex: HttpMessageNotReadableException): ResponseEntity<String> =
        respond(400, ApiErrors.forStatus(400, "Body is not valid JSON", env.isProd))

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaType(ex: HttpMediaTypeNotSupportedException): ResponseEntity<String> =
        respond(415, ApiErrors.forStatus(415, "Unsupported Media Type: ${ex.contentType}", env.isProd))

    @ExceptionHandler(MissingServletRequestParameterException::class, MethodArgumentTypeMismatchException::class)
    fun badParameter(ex: Exception): ResponseEntity<String> =
        respond(400, ApiErrors.forStatus(400, ex.message, env.isProd))

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<String> {
        val status = (ex as? ErrorResponse)?.statusCode?.value() ?: 500
        if (status >= 500) log.error("request failed", ex)
        return respond(status, ApiErrors.forStatus(status, ex.message, env.isProd))
    }

    private fun respond(status: Int, error: ApiError): ResponseEntity<String> =
        ResponseEntity.status(status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(ApiErrors.toJson(error))
}

/**
 * Replaces Spring Boot's /error page for anything that fails outside Spring MVC (the servlet
 * container itself, a filter that throws). Same envelope as the advice.
 */
@RestController
class ApiErrorController(private val env: ServerEnv) : ErrorController {
    @RequestMapping("/error")
    fun error(request: HttpServletRequest): ResponseEntity<String> {
        val status = (request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE) as? Int) ?: 500
        val error = if (status == 404) {
            ApiErrors.NOT_FOUND
        } else {
            val cause = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION) as? Throwable
            ApiErrors.forStatus(status, cause?.message ?: request.getAttribute(RequestDispatcher.ERROR_MESSAGE) as? String, env.isProd)
        }
        return ResponseEntity.status(status)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body(ApiErrors.toJson(error))
    }
}
