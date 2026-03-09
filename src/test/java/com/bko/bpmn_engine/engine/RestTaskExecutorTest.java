package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.model.RestTaskConfiguration;
import com.bko.bpmn_engine.model.ServiceTask;
import com.bko.bpmn_engine.model.ServiceTaskType;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RestTaskExecutor.
 */
class RestTaskExecutorTest {

    private HttpServer server;
    private RestTaskExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        executor = new RestTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private ServiceTask restTask(String url, RestTaskConfiguration config) {
        return new ServiceTask(
                "Task_1", "REST Task", "rest", ServiceTaskType.REST,
                config, null, null, List.of(), List.of()
        );
    }

    @Test
    void execute_http4xx_throwsIllegalStateException() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api", exchange -> {
            byte[] body = "{\"error\":\"Not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        RestTaskConfiguration config = new RestTaskConfiguration(
                "GET", "http://localhost:" + port + "/api",
                null, null, null, null, null, null, null, null, null, null, null, 5
        );
        ServiceTask task = restTask("http://localhost:" + port + "/api", config);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute(task, Map.of()));
        assertTrue(ex.getMessage().contains("404"));
    }

    @Test
    void execute_http5xx_throwsIllegalStateException() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api", exchange -> {
            byte[] body = "Internal Server Error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        RestTaskConfiguration config = new RestTaskConfiguration(
                "GET", "http://localhost:" + port + "/api",
                null, null, null, null, null, null, null, null, null, null, null, 5
        );
        ServiceTask task = restTask("http://localhost:" + port + "/api", config);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> executor.execute(task, Map.of()));
        assertTrue(ex.getMessage().contains("500") || ex.getMessage().contains("status"),
                "Expected status 500 in message: " + ex.getMessage());
    }

    @Test
    void execute_apikeyInHeader_addsHeaderNotQuery() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        var headerRef = new Object[]{null};
        server.createContext("/api", exchange -> {
            headerRef[0] = exchange.getRequestHeaders().getFirst("X-API-Key");
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        int port = server.getAddress().getPort();
        RestTaskConfiguration config = new RestTaskConfiguration(
                "GET", "http://localhost:" + port + "/api",
                "apikey", "header", "X-API-Key", "= apiKey", null, null, null, null, null, null, null, 5
        );
        ServiceTask task = restTask("http://localhost:" + port + "/api", config);

        Object result = executor.execute(task, Map.of("apiKey", "secret-123"));
        assertNotNull(result);
        assertEquals("secret-123", headerRef[0]);
    }

    @Test
    void execute_unsupportedAuthType_throwsIllegalArgumentException() {
        RestTaskConfiguration config = new RestTaskConfiguration(
                "GET", "http://localhost:9999/api",
                "oauth2", null, null, null, null, null, null, null, null, null, null, 5
        );
        ServiceTask task = restTask("http://localhost:9999/api", config);

        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(task, Map.of()));
    }

    @Test
    void execute_nullConfig_throwsIllegalArgumentException() {
        ServiceTask task = new ServiceTask(
                "T1", "REST", "rest", ServiceTaskType.REST,
                null, null, null, List.of(), List.of()
        );
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(task, Map.of()));
    }

    @Test
    void execute_blankUrl_throwsIllegalArgumentException() {
        RestTaskConfiguration config = new RestTaskConfiguration(
                "GET", "= url", null, null, null, null, null, null, null, null, null, null, null, 5
        );
        ServiceTask task = restTask("", config);
        assertThrows(IllegalArgumentException.class,
                () -> executor.execute(task, Map.of("url", "")));
    }
}
