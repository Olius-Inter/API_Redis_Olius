package br.com.olius.olius_redis_api.config;

import br.com.olius.olius_redis_api.model.RankingCategory;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Query-string conversion, service-token interceptor and CORS. */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    private final OliusProperties properties;

    public WebConfiguration(OliusProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, RankingCategory.class, RankingCategory::fromValue);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ServiceTokenInterceptor(properties.serviceToken()))
                .addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (!properties.allowedOrigins().isEmpty()) {
            registry.addMapping("/api/**")
                    .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                    .allowedMethods("GET", "POST")
                    .allowedHeaders("Content-Type", HttpHeaders.AUTHORIZATION, ServiceTokenInterceptor.HEADER);
        }
    }
}
