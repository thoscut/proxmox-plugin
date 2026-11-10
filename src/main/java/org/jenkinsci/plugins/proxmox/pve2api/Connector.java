package org.jenkinsci.plugins.proxmox.pve2api;

import hudson.util.Secret;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.security.auth.login.LoginException;
import kong.unirest.HttpRequest;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import kong.unirest.UnirestInstance;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONException;
import kong.unirest.json.JSONObject;

/**
 * Minimal client for the Proxmox VE API ({@code /api2/json}).
 *
 * <p>A single instance is shared by all agents of a {@code Datacenter}, so the
 * authentication state is guarded and requests can be made from several threads.
 */
public class Connector implements AutoCloseable {

    public static final long WAIT_TIME_MS = 1000;
    static final long DEFAULT_TASK_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes
    static final int DEFAULT_PORT = 8006;
    private static final int CONNECT_TIMEOUT_MS = 10 * 1000;
    static final int SOCKET_TIMEOUT_MS = 60 * 1000;
    // Proxmox tickets are valid for 2 hours; renew a bit earlier to avoid races with the expiry.
    static final long TICKET_LIFETIME_MS = 110 * 60 * 1000;

    private final ProxmoxAuth auth;
    final String baseURL;

    private volatile Ticket ticket;
    private final UnirestInstance unirest;
    private long taskTimeoutMs = DEFAULT_TASK_TIMEOUT_MS;

    private static final Logger LOGGER = Logger.getLogger(Connector.class.getName());

    /** Authentication ticket and CSRF token, which always belong together. */
    private static final class Ticket {
        final String authTicket;
        final String csrfPreventionToken;
        final long issuedAtMs;

        Ticket(String authTicket, String csrfPreventionToken, long issuedAtMs) {
            this.authTicket = authTicket;
            this.csrfPreventionToken = csrfPreventionToken;
            this.issuedAtMs = issuedAtMs;
        }
    }

    public Connector(String hostname, String username, String realm, Secret password) {
        this(hostname, username, realm, password, false);
    }

    public Connector(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        this(hostname, ProxmoxAuth.usernamePassword(username, realm, password), ignoreSSL);
    }

    public Connector(String hostname, ProxmoxAuth auth, Boolean ignoreSSL) {
        int port = DEFAULT_PORT;
        String host = normalizeHost(hostname);
        try {
            URI uri = new URI("https://" + host);
            if (uri.getHost() != null) {
                host = uri.getHost();
            }
            if (uri.getPort() != -1) {
                port = uri.getPort();
            }
        } catch (URISyntaxException e) {
            LOGGER.log(Level.WARNING, "Failed to parse hostname URI: " + hostname, e);
        }
        this.auth = auth;

        this.unirest = Unirest.spawnInstance();
        unirest.config()
                .verifySsl(!Boolean.TRUE.equals(ignoreSSL))
                .connectTimeout(CONNECT_TIMEOUT_MS)
                .socketTimeout(SOCKET_TIMEOUT_MS);

        this.baseURL = "https://" + host + ":" + port + "/api2/json/";
    }

    /**
     * Strips a scheme, a path and surrounding whitespace that users tend to paste
     * into the hostname field, e.g. {@code https://pve.example.com:8006/}.
     */
    static String normalizeHost(String hostname) {
        if (hostname == null) {
            return "";
        }
        String host = hostname.trim();
        int schemeEnd = host.indexOf("://");
        if (schemeEnd >= 0) {
            host = host.substring(schemeEnd + 3);
        }
        int pathStart = host.indexOf('/');
        if (pathStart >= 0) {
            host = host.substring(0, pathStart);
        }
        // A bare IPv6 address needs brackets to be a valid URI host.
        if (host.indexOf(':') != host.lastIndexOf(':') && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return host;
    }

    // Proxmox's own syntax for node names, snapshot names and task ids (UPIDs).
    private static final Pattern NODE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.-]*");
    private static final Pattern SNAPSHOT_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    // UPIDs contain the user id, which may be any non-blank text without ':' or '/', e.g. john.doe+ci@ldap!token.
    // '/' would act as a path separator on the server even when encoded; everything else is encoded.
    private static final Pattern TASK_ID = Pattern.compile("UPID:[^\\s/%]+");

    static String nodeSegment(String node) throws ProxmoxException {
        if (node == null || !NODE_NAME.matcher(node).matches()) {
            throw new ProxmoxException("Invalid Proxmox node name: '" + node + "'");
        }
        return node;
    }

    static String snapshotSegment(String snapshotName) throws ProxmoxException {
        if (snapshotName == null || !SNAPSHOT_NAME.matcher(snapshotName).matches()) {
            throw new ProxmoxException("Invalid snapshot name: '" + snapshotName + "'");
        }
        return snapshotName;
    }

    static String taskSegment(String taskId) throws ProxmoxException {
        if (taskId == null || !TASK_ID.matcher(taskId).matches()) {
            throw new ProxmoxException("Invalid Proxmox task id: '" + taskId + "'");
        }
        return taskId;
    }

    /**
     * Encodes a single path segment of an API URL. Values are additionally validated against
     * Proxmox's own syntax (see {@link #nodeSegment}, {@link #snapshotSegment}, {@link #taskSegment}),
     * because the server decodes the path before routing it, so an encoded slash would still
     * act as a separator there.
     */
    static String encodePathSegment(String segment) throws ProxmoxException {
        if (segment == null || segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
            throw new ProxmoxException("Invalid value for an API path segment: '" + segment + "'");
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '.'
                    || c == '_'
                    || c == '~'
                    || c == ':'
                    || c == '@') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return sb.toString();
    }

    private static String vmidSegment(Integer vmid) throws ProxmoxException {
        if (vmid == null || vmid < 1) {
            throw new ProxmoxException("Invalid virtual machine id: " + vmid);
        }
        return vmid.toString();
    }

    private static String qemuPath(String node, Integer vmid) throws ProxmoxException {
        return "nodes/" + nodeSegment(node) + "/qemu/" + vmidSegment(vmid);
    }

    /** Only for tests, which cannot wait ten minutes for a task to time out. */
    void setTaskTimeoutMs(long taskTimeoutMs) {
        this.taskTimeoutMs = taskTimeoutMs;
    }

    private boolean usesApiToken() {
        return auth instanceof ProxmoxAuth.ApiToken;
    }

    /**
     * Verifies the credentials. With a username and password this obtains an authentication
     * ticket; an API token needs no login, so it is checked with a request to {@code version}.
     */
    public synchronized void login() throws LoginException {
        if (auth == null) {
            throw new LoginException("No Proxmox credentials configured");
        }
        if (usesApiToken()) {
            ProxmoxAuth.ApiToken token = (ProxmoxAuth.ApiToken) auth;
            if (!token.isValidFormat()) {
                throw new LoginException("The API token must have the form user@realm!tokenid=secret");
            }
            try {
                getJSONResource("version");
            } catch (ProxmoxException e) {
                throw loginException("API token was rejected: " + e.getMessage(), e);
            }
            return;
        }
        ProxmoxAuth.UsernamePassword credentials = (ProxmoxAuth.UsernamePassword) auth;
        HttpResponse<JsonNode> response;
        try {
            response = unirest.post(baseURL + "access/ticket")
                    .field("username", credentials.getUserAtRealm())
                    .field("password", credentials.getPlainPassword())
                    .asJson();
        } catch (UnirestException e) {
            LOGGER.log(Level.WARNING, "Could not connect to Proxmox at " + baseURL, e);
            throw loginException("Could not connect to " + baseURL + ": " + e.getMessage(), e);
        }
        if (!response.isSuccess()) {
            throw new LoginException("Login failed with HTTP " + response.getStatus() + " " + response.getStatusText());
        }
        try {
            JSONObject data = response.getBody().getObject().getJSONObject("data");
            ticket = new Ticket(
                    data.getString("ticket"), data.getString("CSRFPreventionToken"), System.currentTimeMillis());
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Failed reading JSON response", e);
            throw loginException("Failed reading JSON response", e);
        }
    }

    private static LoginException loginException(String message, Throwable cause) {
        LoginException e = new LoginException(message);
        e.initCause(cause);
        return e;
    }

    public synchronized void checkIfAuthTicketIsValid() throws LoginException {
        if (usesApiToken()) {
            return;
        }
        Ticket current = ticket;
        if (current == null || System.currentTimeMillis() - current.issuedAtMs >= TICKET_LIFETIME_MS) {
            login();
        }
    }

    private synchronized Ticket validTicket() throws LoginException {
        checkIfAuthTicketIsValid();
        return ticket;
    }

    /** Drops the ticket that got rejected, unless another thread already renewed it. */
    private synchronized void invalidate(Ticket stale) {
        if (ticket == stale) {
            ticket = null;
        }
    }

    private JsonNode execute(String apiUrl, Supplier<HttpRequest<?>> requestFactory)
            throws LoginException, ProxmoxException {
        Ticket t = validTicket();
        HttpResponse<JsonNode> response = send(requestFactory, t);
        if (response.getStatus() == 401 && !usesApiToken()) {
            // The ticket was revoked or expired early, so log in once more and retry.
            invalidate(t);
            response = send(requestFactory, validTicket());
        }
        if (!response.isSuccess()) {
            throw new ProxmoxException("Proxmox API request '" + apiUrl + "' failed with HTTP " + response.getStatus()
                    + " " + response.getStatusText());
        }
        JsonNode body = response.getBody();
        if (body == null || body.getObject() == null) {
            throw new ProxmoxException("Proxmox API request '" + apiUrl + "' returned no JSON object");
        }
        return body;
    }

    private HttpResponse<JsonNode> send(Supplier<HttpRequest<?>> requestFactory, Ticket t) throws ProxmoxException {
        try {
            HttpRequest<?> request = requestFactory.get();
            if (usesApiToken()) {
                request.header("Authorization", ((ProxmoxAuth.ApiToken) auth).getAuthorizationHeader());
            } else {
                request.header("Cookie", "PVEAuthCookie=" + t.authTicket);
                request.header("CSRFPreventionToken", t.csrfPreventionToken);
            }
            return request.asJson();
        } catch (UnirestException e) {
            throw new ProxmoxException("Could not reach Proxmox at " + baseURL + ": " + e.getMessage(), e);
        }
    }

    private JsonNode getJSONResource(String apiUrl) throws LoginException, ProxmoxException {
        return execute(apiUrl, () -> unirest.get(baseURL + apiUrl));
    }

    private JsonNode postJSONResource(String apiUrl, String body) throws LoginException, ProxmoxException {
        return execute(apiUrl, () -> unirest.post(baseURL + apiUrl)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(body));
    }

    private static JSONObject dataObject(JsonNode node, String apiUrl) throws ProxmoxException {
        try {
            return node.getObject().getJSONObject("data");
        } catch (JSONException e) {
            throw new ProxmoxException("Unexpected response from '" + apiUrl + "': " + e.getMessage(), e);
        }
    }

    private static JSONArray dataArray(JsonNode node, String apiUrl) throws ProxmoxException {
        try {
            return node.getObject().getJSONArray("data");
        } catch (JSONException e) {
            throw new ProxmoxException("Unexpected response from '" + apiUrl + "': " + e.getMessage(), e);
        }
    }

    private static String dataString(JsonNode node, String apiUrl) throws ProxmoxException {
        try {
            return node.getObject().getString("data");
        } catch (JSONException e) {
            throw new ProxmoxException("Unexpected response from '" + apiUrl + "': " + e.getMessage(), e);
        }
    }

    public List<String> getNodes() throws LoginException, ProxmoxException {
        List<String> res = new ArrayList<>();
        JSONArray nodes = dataArray(getJSONResource("nodes"), "nodes");
        for (int i = 0; i < nodes.length(); i++) {
            String node = nodes.getJSONObject(i).optString("node", null);
            if (node != null) {
                res.add(node);
            }
        }
        return res;
    }

    public JSONObject getTaskStatus(String node, String taskId) throws LoginException, ProxmoxException {
        String url = "nodes/" + nodeSegment(node) + "/tasks/" + encodePathSegment(taskSegment(taskId)) + "/status";
        return dataObject(getJSONResource(url), url);
    }

    public JSONObject getQemuMachineStatus(String node, Integer vmid) throws LoginException, ProxmoxException {
        String url = qemuPath(node, vmid) + "/status/current";
        return dataObject(getJSONResource(url), url);
    }

    public boolean isQemuMachineRunning(String node, Integer vmid) throws LoginException, ProxmoxException {
        return "running".equals(getQemuMachineStatus(node, vmid).optString("status"));
    }

    /**
     * Polls a task until it is no longer running.
     *
     * @return the final task status
     * @throws ProxmoxException if the task is still running after the timeout
     */
    public JSONObject waitForTaskToFinish(String node, String taskId)
            throws LoginException, InterruptedException, ProxmoxException {
        long startTime = System.currentTimeMillis();
        while (true) {
            JSONObject lastTaskStatus = getTaskStatus(node, taskId);
            if (!"running".equals(lastTaskStatus.optString("status"))) {
                return lastTaskStatus;
            }
            if (System.currentTimeMillis() - startTime > taskTimeoutMs) {
                LOGGER.log(Level.WARNING, "Task " + taskId + " on node " + node + " did not finish in time");
                throw new ProxmoxException("Task " + taskId + " on node " + node + " did not finish within "
                        + (taskTimeoutMs / 1000) + " seconds");
            }
            Thread.sleep(WAIT_TIME_MS);
        }
    }

    /**
     * @return {@code true} if the task status describes a task that finished without error.
     */
    public static boolean isTaskSuccessful(JSONObject taskStatus) {
        return taskStatus != null
                && "stopped".equals(taskStatus.optString("status"))
                && "OK".equals(taskStatus.optString("exitstatus"));
    }

    /**
     * @return the QEMU virtual machines of a node, keyed and sorted by VM id, with their names as values.
     */
    public Map<Integer, String> getQemuMachines(String node) throws LoginException, ProxmoxException {
        Map<Integer, String> res = new TreeMap<>();
        String url = "nodes/" + nodeSegment(node) + "/qemu";
        JSONArray qemuVMs = dataArray(getJSONResource(url), url);
        for (int i = 0; i < qemuVMs.length(); i++) {
            JSONObject vm = qemuVMs.getJSONObject(i);
            if (!vm.has("vmid")) {
                continue;
            }
            int vmid = vm.getInt("vmid");
            res.put(vmid, vm.optString("name", "VM " + vmid));
        }
        return res;
    }

    public List<String> getQemuMachineSnapshots(String node, Integer vmid) throws LoginException, ProxmoxException {
        List<String> res = new ArrayList<>();
        String url = qemuPath(node, vmid) + "/snapshot";
        JSONArray snapshots = dataArray(getJSONResource(url), url);
        for (int i = 0; i < snapshots.length(); i++) {
            String name = snapshots.getJSONObject(i).optString("name", null);
            if (name != null) {
                res.add(name);
            }
        }
        return res;
    }

    public String rollbackQemuMachineSnapshot(String node, Integer vmid, String snapshotName)
            throws LoginException, ProxmoxException {
        String url = qemuPath(node, vmid) + "/snapshot/" + snapshotSegment(snapshotName) + "/rollback";
        return dataString(postJSONResource(url, ""), url);
    }

    public String startQemuMachine(String node, Integer vmid) throws LoginException, ProxmoxException {
        String url = qemuPath(node, vmid) + "/status/start";
        return dataString(postJSONResource(url, ""), url);
    }

    public String stopQemuMachine(String node, Integer vmid) throws LoginException, ProxmoxException {
        String url = qemuPath(node, vmid) + "/status/stop";
        return dataString(postJSONResource(url, ""), url);
    }

    public String shutdownQemuMachine(String node, Integer vmid) throws LoginException, ProxmoxException {
        String url = qemuPath(node, vmid) + "/status/shutdown";
        return dataString(postJSONResource(url, ""), url);
    }

    @Override
    public void close() {
        unirest.shutDown();
    }
}
