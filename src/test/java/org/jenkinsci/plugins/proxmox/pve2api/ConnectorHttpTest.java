package org.jenkinsci.plugins.proxmox.pve2api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import hudson.util.Secret;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.security.auth.login.LoginException;
import kong.unirest.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the requests {@link Connector} sends and how it handles the answers, using a local HTTP
 * server as a stub of the Proxmox API.
 */
class ConnectorHttpTest {

    private static final String UPID = "UPID:pve1:00001234:00000000:00000000:qmtask:100:user@pve:";
    private static final String TOKEN = "jenkins@pve!ci=12345678-aaaa-bbbb-cccc-1234567890ab";

    private HttpServer server;
    private String baseUrl;
    private Connector connector;

    /** Method, path and query of every request, in order. */
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private final Map<String, String> bodies = new ConcurrentHashMap<>();
    private final Map<String, String> authorizations = new ConcurrentHashMap<>();
    private final AtomicInteger logins = new AtomicInteger();
    private volatile boolean rejectLogin;
    private volatile String rejectedTicket;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api2/json/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api2/json/";
        connector = Connector.forBaseUrl(
                baseUrl, ProxmoxAuth.usernamePassword("user", "pve", Secret.fromString("secret")));
    }

    @AfterEach
    void stopServer() {
        connector.close();
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getRawPath().substring("/api2/json/".length());
        String query = exchange.getRequestURI().getRawQuery();
        String method = exchange.getRequestMethod();
        String request = method + " " + path + (query == null ? "" : "?" + query);
        requests.add(request);
        bodies.put(request, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) {
            authorizations.put(request, authorization);
        }
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");

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
        } else if (path.equals("version")) {
            respond(exchange, 200, "{\"data\":{\"version\":\"8.4\"}}");
        } else if (path.equals("nodes")) {
            respond(exchange, 200, "{\"data\":[{\"node\":\"pve1\"},{\"node\":\"pve2\"}]}");
        } else if (path.equals("nodes/pve1/qemu")) {
            respond(
                    exchange,
                    200,
                    "{\"data\":[{\"vmid\":101,\"name\":\"agent\"},{\"vmid\":100,\"name\":\"agent\"},{\"vmid\":102}]}");
        } else if (path.equals("nodes/pve1/qemu/100/agent/exec")) {
            respond(exchange, 200, "{\"data\":{\"pid\":42}}");
        } else if (path.equals("nodes/pve1/qemu/100/agent/exec-status")) {
            respond(exchange, 200, "{\"data\":{\"exited\":1,\"exitcode\":0,\"out-data\":\"hello\"}}");
        } else if (path.equals("nodes/pve1/qemu/100/agent/ping")) {
            respond(exchange, 200, "{\"data\":{\"result\":{}}}");
        } else if (path.startsWith("nodes/pve1/qemu/101/agent/")) {
            respond(exchange, 500, "{\"data\":null,\"message\":\"QEMU guest agent is not running\\n\"}");
        } else if (method.equals("DELETE") && path.equals("nodes/pve1/qemu/101")) {
            respond(exchange, 200, "{\"data\":null}");
        } else if (path.startsWith("nodes/pve1/qemu/100") && !method.equals("GET")) {
            respond(exchange, 200, "{\"data\":\"" + UPID + "\"}");
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

    // ------------------------------------------------------------------
    // Authentication and error handling
    // ------------------------------------------------------------------

    @Test
    void lists_nodes_after_login() throws Exception {
        assertThat(connector.getNodes(), contains("pve1", "pve2"));
        assertThat(requests, contains("POST access/ticket", "GET nodes"));
    }

    @Test
    void reuses_the_auth_ticket() throws Exception {
        connector.getNodes();
        connector.getNodes();
        assertThat(logins.get(), is(1));
    }

    @Test
    void logs_in_again_when_the_ticket_is_rejected() throws Exception {
        connector.getNodes();
        rejectedTicket = "T1";
        assertThat(connector.getNodes(), contains("pve1", "pve2"));
        assertThat(logins.get(), is(2));
    }

    @Test
    void rejected_credentials_throw_a_login_exception() {
        rejectLogin = true;
        assertThrows(LoginException.class, () -> connector.getNodes());
    }

    @Test
    void an_api_token_is_sent_with_every_request_and_needs_no_login() throws Exception {
        try (Connector withToken = Connector.forBaseUrl(baseUrl, ProxmoxAuth.apiToken(Secret.fromString(TOKEN)))) {
            withToken.login();
            assertThat(withToken.getNodes(), contains("pve1", "pve2"));
        }
        assertThat(requests, contains("GET version", "GET nodes"));
        assertThat(authorizations.get("GET nodes"), is("PVEAPIToken=" + TOKEN));
        assertThat(logins.get(), is(0));
    }

    @Test
    void an_http_error_throws_a_proxmox_exception() {
        ProxmoxException e = assertThrows(ProxmoxException.class, () -> connector.getQemuMachineStatus("pve1", 100));
        assertThat(e.getMessage(), containsString("HTTP 500"));
    }

    @Test
    void the_error_message_of_the_response_body_is_reported() {
        ProxmoxException e =
                assertThrows(ProxmoxException.class, () -> connector.executeGuestCommand("pve1", 101, "ls"));
        assertThat(e.getMessage(), containsString("HTTP 500"));
        assertThat(e.getMessage(), containsString("QEMU guest agent is not running"));
    }

    @Test
    void a_connection_error_is_reported_instead_of_an_unchecked_exception() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        }
        String unreachableUrl = "http://127.0.0.1:" + unusedPort + "/api2/json/";

        // With a username and password the login is the first request to fail
        try (Connector unreachable = Connector.forBaseUrl(
                unreachableUrl, ProxmoxAuth.usernamePassword("user", "pve", Secret.fromString("secret")))) {
            LoginException e = assertThrows(LoginException.class, unreachable::getNodes);
            assertThat(e.getMessage(), containsString("Could not connect"));
        }

        // An API token needs no login, so the request itself fails
        try (Connector unreachable =
                Connector.forBaseUrl(unreachableUrl, ProxmoxAuth.apiToken(Secret.fromString(TOKEN)))) {
            ProxmoxException e = assertThrows(ProxmoxException.class, unreachable::getNodes);
            assertThat(e.getMessage(), containsString("Could not reach Proxmox"));
        }
    }

    @Test
    void values_that_could_change_the_endpoint_are_rejected_before_any_request() {
        assertThrows(
                ProxmoxException.class,
                () -> connector.rollbackQemuMachineSnapshot("pve1", 100, "../../status/stop?x#y"));
        assertThrows(ProxmoxException.class, () -> connector.deleteQemuMachineSnapshot("pve1", 100, "a/b"));
        assertThrows(ProxmoxException.class, () -> connector.createQemuMachineSnapshot("pve1", 100, "a b"));
        assertThrows(ProxmoxException.class, () -> connector.cloneQemuMachine("pve1/../pve2", 100, 200, "clone"));
        assertThrows(ProxmoxException.class, () -> connector.cloneQemuMachine("pve1", 100, 0, "clone"));
        assertThrows(ProxmoxException.class, () -> connector.deleteQemuMachine("pve1", null));
        assertThrows(ProxmoxException.class, () -> connector.getGuestCommandStatus("pve1", 100, "42&x=y"));
        assertThat(requests, is(empty()));
    }

    // ------------------------------------------------------------------
    // Virtual machines, snapshots and clones
    // ------------------------------------------------------------------

    @Test
    void lists_virtual_machines_by_id_including_duplicate_and_missing_names() throws Exception {
        Map<Integer, String> machines = connector.getQemuMachines("pve1");
        assertThat(machines.keySet(), contains(100, 101, 102));
        assertThat(machines.get(100), is("agent"));
        assertThat(machines.get(101), is("agent"));
        assertThat(machines.get(102), is("VM 102"));
    }

    @Test
    void creates_a_snapshot_with_form_encoded_parameters() throws Exception {
        assertThat(connector.createQemuMachineSnapshot("pve1", 100, "clean", "before & after=1", true), is(UPID));
        assertThat(
                bodies.get("POST nodes/pve1/qemu/100/snapshot"),
                is("snapname=clean&description=before+%26+after%3D1&vmstate=1"));
    }

    @Test
    void creates_a_snapshot_without_ram_and_description() throws Exception {
        connector.createQemuMachineSnapshot("pve1", 100, "clean", null, false);
        assertThat(bodies.get("POST nodes/pve1/qemu/100/snapshot"), is("snapname=clean"));
    }

    @Test
    void deletes_a_snapshot() throws Exception {
        assertThat(connector.deleteQemuMachineSnapshot("pve1", 100, "clean"), is(UPID));
        assertThat(requests, hasItem("DELETE nodes/pve1/qemu/100/snapshot/clean"));
    }

    @Test
    void rolls_back_to_a_snapshot() throws Exception {
        assertThat(connector.rollbackQemuMachineSnapshot("pve1", 100, "clean"), is(UPID));
        assertThat(requests, hasItem("POST nodes/pve1/qemu/100/snapshot/clean/rollback"));
    }

    @Test
    void clones_a_virtual_machine_as_a_linked_clone_of_a_snapshot() throws Exception {
        assertThat(connector.cloneQemuMachine("pve1", 100, 1001, "build agent", false, "clean"), is(UPID));
        assertThat(
                bodies.get("POST nodes/pve1/qemu/100/clone"), is("newid=1001&name=build+agent&full=0&snapname=clean"));
    }

    @Test
    void clones_a_virtual_machine_from_its_current_state() throws Exception {
        connector.cloneQemuMachine("pve1", 100, 1001, "clone");
        assertThat(bodies.get("POST nodes/pve1/qemu/100/clone"), is("newid=1001&name=clone"));
    }

    @Test
    void converts_pauses_resumes_and_hibernates_a_virtual_machine() throws Exception {
        assertThat(connector.convertQemuMachineToTemplate("pve1", 100), is(UPID));
        assertThat(connector.pauseQemuMachine("pve1", 100), is(UPID));
        assertThat(bodies.get("POST nodes/pve1/qemu/100/status/suspend"), is(""));
        assertThat(connector.hibernateQemuMachine("pve1", 100), is(UPID));
        assertThat(bodies.get("POST nodes/pve1/qemu/100/status/suspend"), is("todisk=1"));
        assertThat(connector.resumeQemuMachine("pve1", 100), is(UPID));
        assertThat(
                requests,
                contains(
                        "POST access/ticket",
                        "POST nodes/pve1/qemu/100/template",
                        "POST nodes/pve1/qemu/100/status/suspend",
                        "POST nodes/pve1/qemu/100/status/suspend",
                        "POST nodes/pve1/qemu/100/status/resume"));
    }

    @Test
    void deletes_a_virtual_machine() throws Exception {
        assertThat(connector.deleteQemuMachine("pve1", 100), is(UPID));
        assertThat(connector.deleteQemuMachine("pve1", 101), is("VM deletion completed"));
        assertThat(requests, hasItem("DELETE nodes/pve1/qemu/100"));
        assertThat(requests, hasItem("DELETE nodes/pve1/qemu/101"));
    }

    // ------------------------------------------------------------------
    // QEMU guest agent
    // ------------------------------------------------------------------

    @Test
    void executes_a_guest_command_as_a_json_argument_vector() throws Exception {
        assertThat(connector.executeGuestCommand("pve1", 100, "ls -la /tmp"), is("42"));

        JSONObject body = new JSONObject(bodies.get("POST nodes/pve1/qemu/100/agent/exec"));
        assertThat(body.getJSONArray("command").toString(), is("[\"/bin/sh\",\"--\",\"-c\",\"ls\",\"-la\",\"/tmp\"]"));
    }

    @Test
    void an_empty_guest_command_is_rejected() {
        assertThrows(ProxmoxException.class, () -> connector.executeGuestCommand("pve1", 100, "  "));
        assertThat(requests, is(empty()));
    }

    @Test
    void reads_the_status_of_a_guest_command() throws Exception {
        JSONObject status = connector.getGuestCommandStatus("pve1", 100, "42");
        assertThat(status.getInt("exitcode"), is(0));
        assertThat(status.getString("out-data"), is("hello"));
        assertThat(requests, hasItem("GET nodes/pve1/qemu/100/agent/exec-status?pid=42"));
    }

    @Test
    void the_guest_agent_is_available_only_if_it_answers_the_ping() throws Exception {
        assertThat(connector.isGuestAgentAvailable("pve1", 100), is(true));
        assertThat(connector.isGuestAgentAvailable("pve1", 101), is(false));
        assertThat(requests, hasItem("POST nodes/pve1/qemu/100/agent/ping"));
        assertThat(authorizations.get("POST nodes/pve1/qemu/100/agent/ping"), is(nullValue()));
        assertThat(requests, not(hasItem("GET nodes/pve1/qemu/100/agent/ping")));
    }
}
