package timeshealth.app.core.network

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.coroutines.Continuation
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT

/** The shape of [TimesHealthApi] as a whole, and how [NetworkFactory] wires it. */
class ApiSurfaceTest {

    private val rig = ApiTestRig()

    @After
    fun tearDown() = rig.close()

    // Static and synthetic members are compiler output (e.g. default-argument bridges), not endpoints.
    private val methods: List<Method> = TimesHealthApi::class.java.declaredMethods
        .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }

    private fun Method.path(): String? =
        getAnnotation(GET::class.java)?.value
            ?: getAnnotation(POST::class.java)?.value
            ?: getAnnotation(PUT::class.java)?.value
            ?: getAnnotation(PATCH::class.java)?.value
            ?: getAnnotation(DELETE::class.java)?.value

    @Test
    fun `every endpoint has a contract case`() {
        assertThat(EndpointContractTest.cases.map { it.name })
            .containsExactlyElementsIn(methods.map { it.name })
    }

    @Test
    fun `every path is relative, so it resolves under the base URL's v1`() {
        methods.forEach { method ->
            val path = method.path()
            assertWithMessage("${method.name} has an HTTP annotation").that(path).isNotNull()
            assertWithMessage("${method.name} path").that(path).doesNotMatch("^/.*")
        }
    }

    @Test
    fun `only config is anonymous, with RN's 8 second deadline`() {
        val config = TimesHealthApi::class.java.getMethod("config", Continuation::class.java)
        assertThat(config.getAnnotation(CallTimeout::class.java)?.millis).isEqualTo(8_000L)

        assertThat(methods.filter { it.isAnnotationPresent(Anonymous::class.java) }.map { it.name })
            .containsExactly("config")
    }

    @Test
    fun `every method is a valid Retrofit method - debug builds validate them at creation`() {
        // validateEagerly parses every method now, instead of on first use in the field.
        NetworkFactory.createApi(
            baseUrl = "https://api.example.invalid/v1",
            tokenProvider = rig.tokenProvider,
            onUnauthorized = {},
            debug = true,
        )
    }

    @Test
    fun `the base URL may or may not end with a slash`() = runTest {
        listOf(rig.baseUrl, "${rig.baseUrl}/").forEach { baseUrl ->
            val api = NetworkFactory.createRetrofit(baseUrl, rig.client, rig.clock).create(TimesHealthApi::class.java)
            rig.enqueueFixture("notifications.json")

            api.notifications()

            assertWithMessage(baseUrl).that(rig.takeRequest().path).isEqualTo("/v1/notifications")
        }
    }

    // ── Query and path encoding ─────────────────────────────────────────────

    @Test
    fun `marathon events without coordinates sends no query at all`() = runTest {
        rig.enqueueFixture("marathon-events.free.json")

        rig.api.marathonEvents()

        assertThat(rig.takeRequest().path).isEqualTo("/v1/marathon/events")
    }

    @Test
    fun `marathon events sends each coordinate that is present and omits a null one`() = runTest {
        rig.enqueueFixture("marathon-events.free.json")
        rig.enqueueFixture("marathon-events.free.json")

        rig.api.marathonEvents(lat = 19.076, lng = null)
        rig.api.marathonEvents(lat = null, lng = 72.8777)

        assertThat(rig.takeRequest().path).isEqualTo("/v1/marathon/events?lat=19.076")
        assertThat(rig.takeRequest().path).isEqualTo("/v1/marathon/events?lng=72.8777")
    }

    @Test
    fun `marathon events coordinates are plain decimals, negative ones included`() = runTest {
        rig.enqueueFixture("marathon-events.free.json")

        rig.api.marathonEvents(lat = -33.8688, lng = 151.2093)

        val url = rig.takeRequest().requestUrl!!
        assertThat(url.queryParameter("lat")).isEqualTo("-33.8688")
        assertThat(url.queryParameter("lng")).isEqualTo("151.2093")
    }

    @Test
    fun `path ids are percent-encoded, so an id can never change the path`() = runTest {
        rig.enqueueFixture("race-detail.unregistered.json")

        rig.api.raceDetail("a/b c?x")

        assertThat(rig.takeRequest().path).isEqualTo("/v1/marathon/events/a%2Fb%20c%3Fx")
    }
}
