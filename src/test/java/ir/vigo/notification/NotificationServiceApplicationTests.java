package ir.vigo.notification;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationServiceApplicationTests {

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private WebEndpointsSupplier endpoints;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void startsWithProductionConfiguration() {
        assertThat(context.getBean(NotificationServiceApplication.class)).isNotNull();
        assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
        assertThat(context.getEnvironment().getProperty("spring.application.name"))
                .isEqualTo("vigo-notification-service");
        assertThat(context.getEnvironment().getProperty("spring.jmx.enabled", Boolean.class)).isFalse();
        assertThat(endpoints.getEndpoints()).extracting(endpoint -> endpoint.getEndpointId().toString())
                .containsExactlyInAnyOrder("health", "info");
    }

    @Test
    void healthIsUpWithoutDetailsOrComponents() throws Exception {
        var response = request("GET", "/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("{\"status\":\"UP\"}"));
    }

    @Test
    void infoIsAvailableWithoutMetadata() throws Exception {
        var response = request("GET", "/actuator/info");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body())).isEqualTo(json.readTree("{}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/env", "/configprops", "/beans", "/mappings", "/metrics",
            "/loggers", "/heapdump", "/threaddump", "/conditions", "/scheduledtasks"})
    void otherManagementRoutesAreUnavailable(String path) throws Exception {
        assertThat(request("GET", "/actuator" + path).statusCode()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/shutdown", "/loggers/ROOT", "/env"})
    void mutatingManagementRoutesAreUnavailable(String path) throws Exception {
        assertThat(request("POST", "/actuator" + path).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .method(method, "POST".equals(method)
                            ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody())
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
