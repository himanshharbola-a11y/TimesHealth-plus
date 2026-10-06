package timeshealth.app.core.network

/**
 * Marks a [TimesHealthApi] method callable before sign-in. [AuthInterceptor] sends no
 * `Authorization` header for it, even when a token is available.
 *
 * Why not just send the token when we have one: GET /config is the force-update and maintenance
 * gate, and it has to work even when an old build's sign-in is what broke. It must never depend
 * on (or wait for) the identity provider. Not sending a token also means a 401 here can never
 * be mistaken for "your session ended".
 *
 * Read from Retrofit's [retrofit2.Invocation] request tag, so nothing extra goes on the wire.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Anonymous

/**
 * Overrides the whole-call deadline (connect, send, wait and read) for one [TimesHealthApi]
 * method. Without it a call gets [NetworkFactory.DEFAULT_CALL_TIMEOUT], the RN client's 15 s.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class CallTimeout(val millis: Long)
