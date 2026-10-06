package timeshealth.server.admin

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Serves the admin dashboard (a static page + script in resources/static/admin) at /admin/ and
 * guards its API (/admin/api/...) with [AdminApiInterceptor].
 */
@Configuration(proxyBeanMethods = false)
class AdminWebConfig(private val interceptor: AdminApiInterceptor) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor)
            .addPathPatterns("/admin/api/**")
            .excludePathPatterns("/admin/api/login", "/admin/api/logout")
    }

    override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
        registry.addResourceHandler("/admin/assets/**")
            .addResourceLocations("classpath:/static/admin/assets/")
            // Revalidated on every load, so a deploy is picked up at once.
            .setCacheControl(CacheControl.noCache())
    }

    /** A strict Content-Security-Policy for the dashboard pages: our own script only. */
    @Bean
    fun adminPageHeaders(): FilterRegistrationBean<OncePerRequestFilter> =
        FilterRegistrationBean<OncePerRequestFilter>(
            object : OncePerRequestFilter() {
                override fun shouldNotFilter(request: HttpServletRequest) = !request.requestURI.startsWith("/admin")

                override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
                    response.setHeader(
                        "Content-Security-Policy",
                        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' https: data:; " +
                            "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
                    )
                    chain.doFilter(request, response)
                }
            },
        ).apply { addUrlPatterns("/admin", "/admin/*") }
}

@Controller
class AdminPageController {
    @GetMapping("/admin")
    fun redirect(response: HttpServletResponse) = response.sendRedirect("/admin/")

    @GetMapping("/admin/")
    fun page(): ResponseEntity<ClassPathResource> = ResponseEntity.ok()
        .contentType(MediaType.TEXT_HTML)
        .cacheControl(CacheControl.noCache())
        .body(ClassPathResource("static/admin/index.html"))
}
