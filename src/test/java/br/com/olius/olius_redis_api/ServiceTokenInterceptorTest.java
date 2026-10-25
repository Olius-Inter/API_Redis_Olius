package br.com.olius.olius_redis_api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.olius.olius_redis_api.config.ServiceTokenInterceptor;
import br.com.olius.olius_redis_api.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ServiceTokenInterceptorTest {

    private static final String TOKEN = "test-secret";

    private final ServiceTokenInterceptor interceptor = new ServiceTokenInterceptor(TOKEN);

    private boolean call(ServiceTokenInterceptor target, String method, String token) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/ranking/rebuild");
        if (token != null) {
            request.addHeader(ServiceTokenInterceptor.HEADER, token);
        }
        return target.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    @Test
    void readsDoNotNeedAToken() {
        assertTrue(call(interceptor, "GET", null));
    }

    @Test
    void writesWithTheRightTokenPass() {
        assertTrue(call(interceptor, "POST", TOKEN));
    }

    @Test
    void writesWithoutOrWithAWrongTokenAreRejected() {
        assertThrows(UnauthorizedException.class, () -> call(interceptor, "POST", null));
        assertThrows(UnauthorizedException.class, () -> call(interceptor, "POST", "guess"));
        assertThrows(UnauthorizedException.class, () -> call(interceptor, "POST", TOKEN + "x"));
    }

    @Test
    void withoutAConfiguredTokenWritesAreBlocked() {
        ServiceTokenInterceptor noToken = new ServiceTokenInterceptor("");
        assertTrue(call(noToken, "GET", null));
        assertThrows(UnauthorizedException.class, () -> call(noToken, "POST", ""));
    }
}
