package br.com.olius.olius_redis_api.config;

import br.com.olius.olius_redis_api.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Protects write routes under /api/**: they require {@code X-Service-Token}
 * equal to OLIUS_SERVICE_TOKEN. Reads stay public. Without a configured
 * token every write is rejected (fail closed).
 */
public class ServiceTokenInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Service-Token";

    private final byte[] expectedToken;

    public ServiceTokenInterceptor(String expectedToken) {
        this.expectedToken = expectedToken == null || expectedToken.isBlank()
                ? null
                : expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (HttpMethod.GET.matches(method) || HttpMethod.HEAD.matches(method) || HttpMethod.OPTIONS.matches(method)) {
            return true;
        }
        if (expectedToken == null) {
            throw new UnauthorizedException("Writes are disabled: set OLIUS_SERVICE_TOKEN.");
        }
        String received = request.getHeader(HEADER);
        // Constant-time comparison.
        if (received == null || !MessageDigest.isEqual(expectedToken, received.getBytes(StandardCharsets.UTF_8))) {
            throw new UnauthorizedException("Missing or invalid " + HEADER + " header.");
        }
        return true;
    }
}
