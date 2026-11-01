package br.com.olius.olius_redis_api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document; only served when OLIUS_API_DOCS_ENABLED=true. */
@Configuration
public class OpenApiConfiguration {

    public static final String SERVICE_TOKEN_SCHEME = "serviceToken";
    public static final String BEARER_SCHEME = "coreBearer";

    @Bean
    public OpenAPI oliusRedisOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Olius - Ranking")
                        .description("Annual ranking by points over Redis. Ranking reads require a Core Bearer token; POST requires the "
                                + ServiceTokenInterceptor.HEADER + " header.")
                        .version("2.0.0"))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Access token issued by the Core API"))
                        .addSecuritySchemes(SERVICE_TOKEN_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ServiceTokenInterceptor.HEADER)
                        .description("OLIUS_SERVICE_TOKEN")));
    }
}
