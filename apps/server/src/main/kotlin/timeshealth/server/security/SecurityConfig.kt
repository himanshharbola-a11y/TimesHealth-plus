package timeshealth.server.security

import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.session.DisableEncodeUrlFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import timeshealth.server.config.ServerEnv
import timeshealth.server.json.KotlinxJsonHttpMessageConverter
import timeshealth.server.json.RawBodyArgumentResolver
import timeshealth.server.web.FastifyCorsFilter
import timeshealth.server.web.RateLimitFilter
import timeshealth.server.web.RequestLogFilter
import timeshealth.server.web.RouteLookup
import timeshealth.server.web.SecurityHeadersFilter

/**
 * Spring Security, stateless, with the request pipeline in Fastify's order (app.ts):
 *
 *   request log → helmet headers → CORS → rate limit → [route] → bearer auth (per route) → handler
 *
 * Every path is `permitAll` at the Spring Security level; authentication is per ROUTE, exactly
 * like `{ preHandler: app.requireAuth }`, decided by [BearerAuthFilter] from the handler's
 * signature (`@CurrentUser` / `@Authenticated`). That keeps Fastify's behaviour that an unknown
 * route is a 404, never a 401, and that a public route ignores a stale token.
 *
 * Spring Security's CSRF, session, header writers, form/basic login and its generated user are
 * all off: this is a bearer-token JSON API.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun routeLookup(mappings: ObjectProvider<RequestMappingHandlerMapping>) = RouteLookup(mappings)

    @Bean
    fun securityFilterChain(http: HttpSecurity, env: ServerEnv, routes: RouteLookup, auth: AuthService): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .cors { it.disable() }
            .headers { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
            .anonymous { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .addFilterBefore(SecurityHeadersFilter(), DisableEncodeUrlFilter::class.java)
            .addFilterAfter(FastifyCorsFilter(env), SecurityHeadersFilter::class.java)
            .addFilterAfter(RateLimitFilter(env, routes), FastifyCorsFilter::class.java)
            .addFilterAfter(BearerAuthFilter(routes, auth, env), RateLimitFilter::class.java)
        return http.build()
    }

    /** Outermost: logs every request, including ones the security chain answers itself. */
    @Bean
    fun requestLogFilter(): FilterRegistrationBean<RequestLogFilter> =
        FilterRegistrationBean(RequestLogFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE }
}

/** kotlinx.serialization first in the converter list; the RawBody and @CurrentUser arguments. */
@Configuration(proxyBeanMethods = false)
class WebMvcConfig : WebMvcConfigurer {
    override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters.removeIf { it.javaClass.name.contains("KotlinSerialization") }
        converters.add(0, KotlinxJsonHttpMessageConverter())
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(RawBodyArgumentResolver())
        resolvers.add(CurrentUserArgumentResolver())
    }
}
