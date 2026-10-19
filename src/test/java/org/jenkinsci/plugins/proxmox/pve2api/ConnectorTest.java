package org.jenkinsci.plugins.proxmox.pve2api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.login.LoginException;
import kong.unirest.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Connector}, using a local HTTP server as Proxmox API stub.
 */
class ConnectorTest {

    private HttpServer server;
    private Connector connector;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger logins = new AtomicInteger();
    private volatile boolean rejectLogin;
    private volatile String rejectedTicket;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api2/json/", this::handle);
        server.start();
        URI baseURI = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api2/json/");
        connector = new Connector(baseURI, "user", "pve", Secret.fromString("secret"), false);
    }

    @AfterEach
    void stopServer() {
        connector.close();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath().substring("/api2/json/".length());
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        requests.add(exchange.getRequestMethod() + " " + path);

        if (path.equals("access/ticket")) {
            if (rejectLogin) {
                respond(exchange, 401, "{\"data\":null}");
            } else {
                int login = logins.incrementAndGet();
                respond(exchange, 200, "{\"data\":{\"ticket\":\"T" + login + "\",\"CSRFPreventionToken\":\"C" + login
                        + "\"}}");
            }
        } else if (rejectedTicket != null && ("PVEAuthCookie=" + rejectedTicket).equals(cookie)) {
            respond(exchange, 401, "{\"data\":null}");
        } else if (path.equals("nodes")) {
            respond(exchange, 200, "{\"data\":[{\"node\":\"pve1\"},{\"node\":\"pve2\"}]}");
        } else if (path.equals("nodes/pve1/qemu")) {
            respond(
                    exchange,
                    200,
                    "{\"data\":[{\"vmid\":101,\"name\":\"agent\"},{\"vmid\":100,\"name\":\"agent\"},{\"vmid\":102}]}");
        } else if (path.endsWith("/rollback")) {
            respond(exchange, 200, "{\"data\":\"UPID:pve1:00001234:00000000:00000000:qmrollback:100:user@pve:\"}");
        } else {
            respond(exchange, 500, "{\"data\":null}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void lists_nodes_after_login() throws Exception {
        assertThat(connector.getNodes(), contains("pve1", "pve2"));
        assertThat(requests, contains("POST access/ticket", "GET nodes"));
    }

    @Test
    void reuses_auth_ticket() throws Exception {
        connector.getNodes();
        connector.getNodes();
        assertThat(logins.get(), is(1));
    }

    @Test
    void lists_virtual_machines_by_id_including_duplicate_names() throws Exception {
        Map<Integer, String> machines = connector.getQemuMachines("pve1");
        assertThat(machines.keySet(), contains(100, 101, 102));
        assertThat(machines.get(100), is("agent"));
        assertThat(machines.get(101), is("agent"));
        assertThat(machines.get(102), is(""));
    }

    @Test
    void rejected_credentials_throw_login_exception() {
        rejectLogin = true;
        assertThrows(LoginException.class, () -> connector.getNodes());
    }

    @Test
    void http_error_throws_proxmox_exception() {
        ProxmoxException e = assertThrows(ProxmoxException.class, () -> connector.getQemuMachineStatus("pve1", 100));
        assertThat(e.getMessage(), containsString("HTTP 500"));
    }

    @Test
    void connection_error_throws_proxmox_exception() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        }
        try (Connector unreachable = new Connector(
                URI.create("http://127.0.0.1:" + unusedPort + "/api2/json/"),
                "user",
                "pve",
                Secret.fromString("secret"),
                false)) {
            assertThrows(ProxmoxException.class, unreachable::getNodes);
        }
    }

    @Test
    void logs_in_again_when_ticket_is_rejected() throws Exception {
        connector.getNodes();
        rejectedTicket = "T1";
        assertThat(connector.getNodes(), contains("pve1", "pve2"));
        assertThat(logins.get(), is(2));
    }

    @Test
    void encodes_path_segments() throws Exception {
        connector.rollbackQemuMachineSnapshot("pve1", 100, "../../status/stop?x#y");
        assertThat(requests, hasItem("POST nodes/pve1/qemu/100/snapshot/..%2F..%2Fstatus%2Fstop%3Fx%23y/rollback"));
    }

    @Test
    void rejects_invalid_path_values() {
        assertThrows(IllegalArgumentException.class, () -> connector.rollbackQemuMachineSnapshot("pve1", 100, ""));
        assertThrows(IllegalArgumentException.class, () -> connector.startQemuMachine(null, 100));
        assertThrows(IllegalArgumentException.class, () -> connector.startQemuMachine("pve1", null));
        assertThrows(IllegalArgumentException.class, () -> connector.startQemuMachine("pve1", 0));
    }

    @Test
    void builds_base_url_from_hostname() {
        assertThat(Connector.buildBaseURL("pve.example.com").toString(), is("https://pve.example.com:8006/api2/json/"));
        assertThat(
                Connector.buildBaseURL(" pve.example.com:8443/ ").toString(),
                is("https://pve.example.com:8443/api2/json/"));
        assertThat(Connector.buildBaseURL("[::1]:8006").toString(), is("https://[::1]:8006/api2/json/"));
    }

    @Test
    void rejects_invalid_hostnames() {
        for (String hostname : new String[] {null, "", "  ", "pve/path", "user@pve", "pve?x=1", "pve#x"}) {
            assertThrows(IllegalArgumentException.class, () -> Connector.buildBaseURL(hostname), hostname);
        }
    }

    @Test
    void task_success_requires_stopped_task_with_ok_or_warnings() {
        assertTrue(Connector.isTaskSuccessful(new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"OK\"}")));
        assertTrue(Connector.isTaskSuccessful(
                new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"WARNINGS: 1\"}")));
        assertFalse(Connector.isTaskSuccessful(
                new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"snapshot does not exist\"}")));
        assertFalse(Connector.isTaskSuccessful(new JSONObject("{\"status\":\"running\"}")));
        assertFalse(Connector.isTaskSuccessful(null));
    }
}
