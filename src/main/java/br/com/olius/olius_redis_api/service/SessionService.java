package br.com.olius.olius_redis_api.service;

import br.com.olius.olius_redis_api.config.OliusProperties;
import br.com.olius.olius_redis_api.exception.CoreUnavailableException;
import br.com.olius.olius_redis_api.exception.InvalidSessionException;
import br.com.olius.olius_redis_api.model.RankingCategory;
import br.com.olius.olius_redis_api.model.UserSession;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Identifies the caller by forwarding the app's Bearer token to the Core
 * ({@code GET /api/v1/auth/session}), which validates JWT, session and account.
 */
@Service
public class SessionService {

    static final String SESSION_PATH = "/api/v1/auth/session";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final OliusProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public SessionService(OliusProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    /** Returns null without an Authorization header: the ranking stays public, only without myPosition. */
    public UserSession identify(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new InvalidSessionException();
        }
        String baseUrl = properties.coreBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new CoreUnavailableException("Set OLIUS_CORE_BASE_URL to validate sessions.");
        }
        try {
            String body = client.get()
                    .uri(baseUrl.replaceAll("/+$", "") + SESSION_PATH)
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve()
                    .body(String.class);
            return parseSession(objectMapper, body);
        } catch (RestClientResponseException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                throw new InvalidSessionException();
            }
            throw new CoreUnavailableException("Core answered " + e.getStatusCode().value() + " to the session check.");
        } catch (RestClientException e) {
            throw new CoreUnavailableException("Could not reach the Core to validate the session.");
        }
    }

    /** Reads only userId and userType, so new Core fields do not break parsing. */
    static UserSession parseSession(ObjectMapper objectMapper, String body) {
        JsonNode json = objectMapper.readTree(body == null ? "" : body);
        String userId = json.path("userId").asString(null);
        if (userId == null || userId.isBlank()) {
            throw new CoreUnavailableException("Core session response without userId.");
        }
        return new UserSession(userId, RankingCategory.fromUserType(json.path("userType").asString(null)));
    }
}
