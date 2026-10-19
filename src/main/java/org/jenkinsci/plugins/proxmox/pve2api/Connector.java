package org.jenkinsci.plugins.proxmox.pve2api;

import hudson.util.Secret;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
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

public class Connector implements AutoCloseable {

    public static final long WAIT_TIME_MS = 1000;
    private static final long TASK_TIMEOUT_MS = 10 * 60 * 1000; // 10 minutes
    // Tickets are valid for 2 hours, renew them a bit earlier to avoid expiry of in-flight requests
    private static final long AUTH_TICKET_LIFETIME_MS = 110 * 60 * 1000;
    private static final int DEFAULT_PORT = 8006;

    protected Integer port;
    protected String username;
    protected String realm;
    protected Secret password;
    protected String baseURL;

    private String authTicket;
    private long authTicketIssuedTimestamp;
    private String csrfPreventionToken;
    private final UnirestInstance unirest;

    private static final Logger LOGGER = Logger.getLogger(Connector.class.getName());

    public Connector(String hostname, String username, String realm, Secret password) {
        this(hostname, username, realm, password, false);
    }

    public Connector(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        this(buildBaseURL(hostname), username, realm, password, Boolean.TRUE.equals(ignoreSSL));
    }

    Connector(URI baseURI, String username, String realm, Secret password, boolean ignoreSSL) {
        this.port = baseURI.getPort();
        this.username = username;
        this.realm = realm;
        this.password = password;
        this.baseURL = baseURI.toString();

        this.unirest = Unirest.spawnInstance();
        unirest.config().verifySsl(!ignoreSSL);
    }

    /**
     * Builds the API base URL from the configured hostname, which may contain a port
     * ({@code host}, {@code host:port}, {@code [ipv6]:port}).
     *
     * @throws IllegalArgumentException if the hostname is empty or contains anything besides host and port.
     */
    static URI buildBaseURL(String hostname) {
        String host = hostname == null ? "" : hostname.trim();
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Hostname not specified");
        }
        try {
            URI uri = new URI("https://" + host);
            if (uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || !uri.getRawPath().isEmpty()
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                throw new IllegalArgumentException("Invalid hostname: " + host);
            }
            int port = uri.getPort() != -1 ? uri.getPort() : DEFAULT_PORT;
            return new URI("https", null, uri.getHost(), port, "/api2/json/", null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid hostname: " + host, e);
        }
    }

    public synchronized void login() throws LoginException, ProxmoxException {
        HttpResponse<JsonNode> response;
        try {
            response = unirest.post(baseURL + "access/ticket")
                    .field("username", username + "@" + realm)
                    .field("password", Secret.toString(password))
                    .asJson();
        } catch (UnirestException e) {
            throw new ProxmoxException("Could not connect to Proxmox at " + baseURL + ": " + e.getMessage(), e);
        }
        if (response.getStatus() == 401) {
            throw new LoginException("Authentication failed for user " + username + "@" + realm);
        }
        JSONObject data = getData(response, "access/ticket").optJSONObject("data");
        if (data == null || !data.has("ticket") || !data.has("CSRFPreventionToken")) {
            throw new LoginException("Authentication failed for user " + username + "@" + realm);
        }
        authTicket = data.getString("ticket");
        csrfPreventionToken = data.getString("CSRFPreventionToken");
        authTicketIssuedTimestamp = System.currentTimeMillis();
    }

    public synchronized void checkIfAuthTicketIsValid() throws LoginException, ProxmoxException {
        if (authTicket == null || System.currentTimeMillis() - authTicketIssuedTimestamp >= AUTH_TICKET_LIFETIME_MS) {
            login();
        }
    }

    private synchronized void invalidateAuthTicket() {
        authTicket = null;
    }

    private HttpResponse<JsonNode> execute(Supplier<HttpRequest<?>> request) throws LoginException, ProxmoxException {
        String ticket;
        String csrfToken;
        synchronized (this) {
            checkIfAuthTicketIsValid();
            ticket = authTicket;
            csrfToken = csrfPreventionToken;
        }
        HttpResponse<JsonNode> response = send(request.get(), ticket, csrfToken);
        if (response.getStatus() == 401) {
            // The ticket may have been invalidated on the server side, so login again and retry once
            synchronized (this) {
                invalidateAuthTicket();
                checkIfAuthTicketIsValid();
                ticket = authTicket;
                csrfToken = csrfPreventionToken;
            }
            response = send(request.get(), ticket, csrfToken);
        }
        return response;
    }

    private HttpResponse<JsonNode> send(HttpRequest<?> request, String ticket, String csrfToken)
            throws ProxmoxException {
        try {
            return request.header("Cookie", "PVEAuthCookie=" + ticket)
                    .header("CSRFPreventionToken", csrfToken)
                    .asJson();
        } catch (UnirestException e) {
            throw new ProxmoxException("Could not connect to Proxmox at " + baseURL + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the JSON object of a successful response.
     *
     * @throws ProxmoxException if the request failed or the body is not a JSON object.
     */
    private static JSONObject getData(HttpResponse<JsonNode> response, String apiUrl) throws ProxmoxException {
        if (!response.isSuccess()) {
            throw new ProxmoxException("Proxmox API request '" + apiUrl + "' failed: HTTP " + response.getStatus()
                    + " " + response.getStatusText());
        }
        if (response.getParsingError().isPresent()
                || response.getBody() == null
                || response.getBody().getObject() == null) {
            throw new ProxmoxException("Proxmox API request '" + apiUrl + "' returned an invalid response");
        }
        return response.getBody().getObject();
    }

    private JSONObject getJSONResource(String apiUrl) throws LoginException, ProxmoxException {
        return getData(execute(() -> unirest.get(baseURL + apiUrl)), apiUrl);
    }

    private JSONObject postJSONResource(String apiUrl) throws LoginException, ProxmoxException {
        return getData(
                execute(() -> unirest.post(baseURL + apiUrl)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body("")),
                apiUrl);
    }

    private static JSONObject getDataObject(JSONObject response, String apiUrl) throws ProxmoxException {
        try {
            return response.getJSONObject("data");
        } catch (JSONException e) {
            throw new ProxmoxException("Unexpected response from Proxmox API request '" + apiUrl + "'", e);
        }
    }

    private static JSONArray getDataArray(JSONObject response, String apiUrl) throws ProxmoxException {
        try {
            return response.getJSONArray("data");
        } catch (JSONException e) {
            throw new ProxmoxException("Unexpected response from Proxmox API request '" + apiUrl + "'", e);
        }
    }

    private static String getDataString(JSONObject response, String apiUrl) throws ProxmoxException {
        try {
            return response.getString("data");
        } catch (JSONException e) {
            throw new ProxmoxException("Unexpected response from Proxmox API request '" + apiUrl + "'", e);
        }
    }

    public List<String> getNodes() throws LoginException, ProxmoxException {
        List<String> res = new ArrayList<String>();
        String apiUrl = "nodes";
        JSONArray nodes = getDataArray(getJSONResource(apiUrl), apiUrl);
        for (int i = 0; i < nodes.length(); i++) {
            res.add(nodes.getJSONObject(i).getString("node"));
        }
        return res;
    }

    public JSONObject getTaskStatus(String node, String taskId) throws LoginException, ProxmoxException {
        String apiUrl = "nodes/" + node + "/tasks/" + taskId + "/status";
        return getDataObject(getJSONResource(apiUrl), apiUrl);
    }

    public JSONObject getQemuMachineStatus(String node, Integer vmid) throws LoginException, ProxmoxException {
        String apiUrl = "nodes/" + node + "/qemu/" + vmid + "/status/current";
        return getDataObject(getJSONResource(apiUrl), apiUrl);
    }

    public Boolean isQemuMachineRunning(String node, Integer vmid) throws LoginException, ProxmoxException {
        return "running".equals(getQemuMachineStatus(node, vmid).optString("status"));
    }

    /**
     * Polls the task status until the task is no longer running or {@link #TASK_TIMEOUT_MS} elapsed.
     *
     * @return the last task status; check its {@code exitstatus} to see whether the task succeeded.
     */
    public JSONObject waitForTaskToFinish(String node, String taskId)
            throws LoginException, ProxmoxException, InterruptedException {
        JSONObject lastTaskStatus = null;
        boolean isRunning = true;
        long startTime = System.currentTimeMillis();
        while (isRunning) {
            lastTaskStatus = getTaskStatus(node, taskId);
            isRunning = "running".equals(lastTaskStatus.optString("status"));
            if (isRunning) {
                if (System.currentTimeMillis() - startTime > TASK_TIMEOUT_MS) {
                    LOGGER.log(Level.WARNING, "Task " + taskId + " on node " + node
                            + " did not finish within timeout period");
                    break;
                }
                Thread.sleep(WAIT_TIME_MS);
            }
        }
        return lastTaskStatus;
    }

    /**
     * @return whether the given task status describes a task that finished successfully.
     */
    public static boolean isTaskSuccessful(JSONObject taskStatus) {
        return taskStatus != null
                && "stopped".equals(taskStatus.optString("status"))
                && "OK".equals(taskStatus.optString("exitstatus"));
    }

    /**
     * @return the virtual machines of the node, mapped from VM id to VM name and ordered by VM id.
     */
    public Map<Integer, String> getQemuMachines(String node) throws LoginException, ProxmoxException {
        Map<Integer, String> res = new TreeMap<Integer, String>();
        String apiUrl = "nodes/" + node + "/qemu";
        JSONArray qemuVMs = getDataArray(getJSONResource(apiUrl), apiUrl);
        for (int i = 0; i < qemuVMs.length(); i++) {
            JSONObject vm = qemuVMs.getJSONObject(i);
            res.put(vm.getInt("vmid"), vm.optString("name", ""));
        }
        return res;
    }

    public List<String> getQemuMachineSnapshots(String node, Integer vmid) throws LoginException, ProxmoxException {
        List<String> res = new ArrayList<String>();
        String apiUrl = "nodes/" + node + "/qemu/" + vmid + "/snapshot";
        JSONArray snapshots = getDataArray(getJSONResource(apiUrl), apiUrl);
        for (int i = 0; i < snapshots.length(); i++) {
            res.add(snapshots.getJSONObject(i).getString("name"));
        }
        return res;
    }

    public String rollbackQemuMachineSnapshot(String node, Integer vmid, String snapshotName)
            throws LoginException, ProxmoxException {
        String apiUrl = "nodes/" + node + "/qemu/" + vmid + "/snapshot/" + snapshotName + "/rollback";
        return getDataString(postJSONResource(apiUrl), apiUrl);
    }

    public String startQemuMachine(String node, Integer vmid) throws LoginException, ProxmoxException {
        String apiUrl = "nodes/" + node + "/qemu/" + vmid + "/status/start";
        return getDataString(postJSONResource(apiUrl), apiUrl);
    }

    public String stopQemuMachine(String node, Integer vmid) throws LoginException, ProxmoxException {
        String apiUrl = "nodes/" + node + "/qemu/" + vmid + "/status/stop";
        return getDataString(postJSONResource(apiUrl), apiUrl);
    }

    public String shutdownQemuMachine(String node, Integer vmid) throws LoginException, ProxmoxException {
        String apiUrl = "nodes/" + node + "/qemu/" + vmid + "/status/shutdown";
        return getDataString(postJSONResource(apiUrl), apiUrl);
    }

    @Override
    public void close() {
        unirest.shutDown();
    }
}
