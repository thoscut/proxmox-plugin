package org.jenkinsci.plugins.proxmox.pve2api;

import hudson.util.Secret;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import kong.unirest.HttpRequest;
import kong.unirest.HttpRequestWithBody;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.MultipartBody;
import kong.unirest.Unirest;
import kong.unirest.UnirestInstance;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;

public class Connector {

    public static final long WAIT_TIME_MS = 1000;

    protected Integer port;
    protected String username;
    protected String realm;
    protected Secret password;
    protected String baseURL;

    private String authTicket;
    private Date authTicketIssuedTimestamp;
    private String csrfPreventionToken;
    private UnirestInstance unirest;

    private static final Logger LOGGER = Logger.getLogger(Connector.class.getName());

    public Connector(String hostname, String username, String realm, Secret password) {
        this(hostname, username, realm, password, false);
    }

    public Connector(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        this.port = 8006;
        // Parse hostname for port information
        try {
            URI uri = new URI("https://" + hostname);
            if (uri.getPort() != -1) {
                hostname = uri.getHost();
                port = uri.getPort();
            }
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        this.username = username;
        this.realm = realm;
        this.password = password;

        this.unirest = Unirest.spawnInstance();
        unirest.config().verifySsl(!ignoreSSL).reset();

        this.authTicketIssuedTimestamp = null;
        this.baseURL = "https://" + hostname + ":" + port.toString() + "/api2/json/";
    }

    public void login() throws LoginException {
        JSONObject authTickets = unirest.post(baseURL + "access/ticket")
                .field("username", username + "@" + realm)
                .field("password", password.getPlainText())
                .asJson()
                .getBody()
                .getObject();
        try {
            JSONObject data = authTickets.getJSONObject("data");
            authTicket = data.get("ticket").toString();
            csrfPreventionToken = data.get("CSRFPreventionToken").toString();
            authTicketIssuedTimestamp = new Date();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed reading JSON response", e);
            throw new LoginException("Failed reading JSON response");
        }
    }

    public void checkIfAuthTicketIsValid() throws LoginException {
        // Authentication ticket has a lifetime of 2 hours, so login again when it expires
        if (authTicketIssuedTimestamp == null
                || authTicketIssuedTimestamp.getTime() <= (new Date().getTime() - (120 * 60 * 1000))) {
            login();
        }
    }

    private HttpResponse<JsonNode> JSONResource(HttpRequest req) throws LoginException {
        checkIfAuthTicketIsValid();
        return req.header("Cookie", "PVEAuthCookie=" + authTicket)
                .header("CSRFPreventionToken", csrfPreventionToken)
                .asJson();
    }

    private JsonNode getJSONResource(String apiUrl) throws LoginException {
        return JSONResource(unirest.get(baseURL + apiUrl)).getBody();
    }

    private JsonNode postJSONResource(String apiUrl, String body) throws LoginException {
        return JSONResource(unirest.post(baseURL + apiUrl)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body(body))
                .getBody();
    }

    public List<String> getNodes() throws LoginException {
        List<String> res = new ArrayList<String>();
        JSONArray nodes = getJSONResource("nodes").getObject().getJSONArray("data");
        for (int i = 0; i < nodes.length(); i++) {
            res.add(nodes.getJSONObject(i).getString("node"));
        }
        return res;
    }
    
    public JSONObject getNodeStatus(String node) throws LoginException {
        JsonNode response = getJSONResource("nodes/" + node + "/status");
        return response.getObject().getJSONObject("data");
    }
    
    public JSONObject getClusterStatus() throws LoginException {
        JsonNode response = getJSONResource("cluster/status");
        return response.getObject();
    }
    
    public JSONObject getNodeResources(String node) throws LoginException {
        JsonNode response = getJSONResource("nodes/" + node + "/rrd?timeframe=hour");
        return response.getObject();
    }
    
    public List<JSONObject> getQemuMachineDetails(String node) throws LoginException {
        List<JSONObject> res = new ArrayList<>();
        JSONArray qemuVMs = getJSONResource("nodes/" + node + "/qemu").getObject().getJSONArray("data");
        for (int i = 0; i < qemuVMs.length(); i++) {
            res.add(qemuVMs.getJSONObject(i));
        }
        return res;
    }

    public JSONObject getTaskStatus(String node, String taskId) throws LoginException {
        JsonNode response = getJSONResource("nodes/" + node + "/tasks/" + taskId + "/status");
        return response.getObject().getJSONObject("data");
    }

    public JSONObject getQemuMachineStatus(String node, Integer vmid) throws LoginException {
        JsonNode response = getJSONResource("nodes/" + node + "/qemu/" + vmid + "/status/current");
        return response.getObject().getJSONObject("data");
    }

    public Boolean isQemuMachineRunning(String node, Integer vmid) throws LoginException {
        JSONObject QemuMachineStatus = null;
        Boolean isRunning = true;
        QemuMachineStatus = getQemuMachineStatus(node, vmid);
        isRunning = (QemuMachineStatus.getString("status").equals("running"));
        return isRunning;
    }

    public JSONObject waitForTaskToFinish(String node, String taskId) throws LoginException, InterruptedException {
        JSONObject lastTaskStatus = null;
        Boolean isRunning = true;
        while (isRunning) {
            lastTaskStatus = getTaskStatus(node, taskId);
            isRunning = (lastTaskStatus.getString("status").equals("running"));
            if (isRunning) {
                Thread.sleep(WAIT_TIME_MS);
            }
        }
        return lastTaskStatus;
    }

    public HashMap<String, Integer> getQemuMachines(String node) throws LoginException {
        HashMap<String, Integer> res = new HashMap<String, Integer>();
        JSONArray qemuVMs =
                getJSONResource("nodes/" + node + "/qemu").getObject().getJSONArray("data");
        for (int i = 0; i < qemuVMs.length(); i++) {
            JSONObject vm = qemuVMs.getJSONObject(i);
            res.put(vm.getString("name"), vm.getInt("vmid"));
        }
        return res;
    }

    public List<String> getQemuMachineSnapshots(String node, Integer vmid) throws LoginException {
        List<String> res = new ArrayList<String>();
        JSONArray snapshots = getJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/snapshot")
                .getObject()
                .getJSONArray("data");
        for (int i = 0; i < snapshots.length(); i++) {
            res.add(snapshots.getJSONObject(i).getString("name"));
        }
        return res;
    }

    public String createQemuMachineSnapshot(String node, Integer vmid, String snapshotName) throws LoginException {
        return createQemuMachineSnapshot(node, vmid, snapshotName, null, true);
    }

    public String createQemuMachineSnapshot(String node, Integer vmid, String snapshotName, String description, boolean includeRam) throws LoginException {
        String body = "snapname=" + snapshotName;
        if (description != null && !description.isEmpty()) {
            body += "&description=" + description;
        }
        if (includeRam) {
            body += "&vmstate=1";
        }

        JsonNode response = postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/snapshot", body);
        JSONObject responseObj = response.getObject();

        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during snapshot creation: " + responseObj.toString());
        }

        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for snapshot creation operation. Response: " + responseObj.toString());
        }

        return responseObj.getString("data");
    }

    public String deleteQemuMachineSnapshot(String node, Integer vmid, String snapshotName) throws LoginException {
        HttpResponse<JsonNode> response = JSONResource(unirest.delete(baseURL + "nodes/" + node + "/qemu/" + vmid.toString() + "/snapshot/" + snapshotName));
        JSONObject responseObj = response.getBody().getObject();

        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during snapshot deletion: " + responseObj.toString());
        }

        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for snapshot deletion operation. Response: " + responseObj.toString());
        }

        return responseObj.getString("data");
    }

    public String rollbackQemuMachineSnapshot(String node, Integer vmid, String snapshotName) throws LoginException {
        JsonNode response = postJSONResource(
                "nodes/" + node + "/qemu/" + vmid.toString() + "/snapshot/" + snapshotName + "/rollback", "");
        JSONObject responseObj = response.getObject();
        
        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during snapshot rollback: " + responseObj.toString());
        }
        
        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for snapshot rollback operation. Response: " + responseObj.toString());
        }
        
        return responseObj.getString("data");
    }

    public String startQemuMachine(String node, Integer vmid) throws LoginException {
        JsonNode response = postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/status/start", "");
        JSONObject responseObj = response.getObject();
        
        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during VM start: " + responseObj.toString());
        }
        
        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for VM start operation. Response: " + responseObj.toString());
        }
        
        return responseObj.getString("data");
    }

    public String stopQemuMachine(String node, Integer vmid) throws LoginException {
        JsonNode response = postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/status/stop", "");
        JSONObject responseObj = response.getObject();
        
        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during VM stop: " + responseObj.toString());
        }
        
        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for VM stop operation. Response: " + responseObj.toString());
        }
        
        return responseObj.getString("data");
    }

    public String shutdownQemuMachine(String node, Integer vmid) throws LoginException {
        JsonNode response = postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/status/shutdown", "");
        JSONObject responseObj = response.getObject();
        
        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during VM shutdown: " + responseObj.toString());
        }
        
        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for VM shutdown operation. Response: " + responseObj.toString());
        }
        
        return responseObj.getString("data");
    }

    public String cloneQemuMachine(String node, Integer vmid, Integer newid, String name) throws LoginException {
        return cloneQemuMachine(node, vmid, newid, name, true);
    }

    public String cloneQemuMachine(String node, Integer vmid, Integer newid, String name, boolean fullClone) throws LoginException {
        return cloneQemuMachine(node, vmid, newid, name, fullClone, null);
    }

    public String cloneQemuMachine(String node, Integer vmid, Integer newid, String name, boolean fullClone, String snapname) throws LoginException {
        String body = "newid=" + newid + "&name=" + name;
        if (!fullClone) {
            body += "&full=0";
        }
        if (snapname != null && !snapname.isEmpty()) {
            body += "&snapname=" + snapname;
        }

        JsonNode response = postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/clone", body);
        JSONObject responseObj = response.getObject();

        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during clone: " + responseObj.toString());
        }

        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            String errorMessage = "Proxmox API returned null data for clone operation";

            // Check if this is a snapshot-related error
            if (responseObj.has("message")) {
                String message = responseObj.getString("message");
                if (message.contains("snapshot") && message.contains("does not exist")) {
                    if (snapname != null && !snapname.isEmpty()) {
                        if ("current".equals(snapname)) {
                            errorMessage = "Clone operation failed: Proxmox API reports that 'current' snapshot does not exist on VM " + vmid +
                                          " on node '" + node + "'. This is unexpected since 'current' should always be available. " +
                                          "Try cloning without specifying a snapshot, or check if the template VM is in a consistent state.";
                        } else {
                            errorMessage = "Clone operation failed: Snapshot '" + snapname + "' does not exist on VM " + vmid +
                                          " on node '" + node + "'. Please verify the snapshot exists before attempting to clone.";
                        }
                    } else {
                        errorMessage = "Clone operation failed: " + message;
                    }
                } else {
                    errorMessage = "Clone operation failed: " + message;
                }
            }

            throw new RuntimeException(errorMessage + " Response: " + responseObj.toString());
        }

        return responseObj.getString("data");
    }

    public String convertQemuMachineToTemplate(String node, Integer vmid) throws LoginException {
        JsonNode response = postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/template", "");
        JSONObject responseObj = response.getObject();

        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during convert to template: " + responseObj.toString());
        }

        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for convert to template operation. Response: " + responseObj.toString());
        }

        return responseObj.getString("data");
    }

    public String pauseQemuMachine(String node, Integer vmid) throws LoginException {
        JsonNode response = postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/status/suspend", "");
        JSONObject responseObj = response.getObject();

        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during VM pause: " + responseObj.toString());
        }

        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for VM pause operation. Response: " + responseObj.toString());
        }

        return responseObj.getString("data");
    }

    public String resumeQemuMachine(String node, Integer vmid) throws LoginException {
        JsonNode response = postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/status/resume", "");
        JSONObject responseObj = response.getObject();

        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during VM resume: " + responseObj.toString());
        }

        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for VM resume operation. Response: " + responseObj.toString());
        }

        return responseObj.getString("data");
    }

    public String hibernateQemuMachine(String node, Integer vmid) throws LoginException {
        String body = "todisk=1";
        JsonNode response = postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/status/suspend", body);
        JSONObject responseObj = response.getObject();

        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during VM hibernation: " + responseObj.toString());
        }

        // Check if data field exists and is not null
        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for VM hibernation operation. Response: " + responseObj.toString());
        }

        return responseObj.getString("data");
    }

    public String deleteQemuMachine(String node, Integer vmid) throws LoginException {
        HttpResponse<JsonNode> response = JSONResource(unirest.delete(baseURL + "nodes/" + node + "/qemu/" + vmid.toString()));
        JSONObject responseObj = response.getBody().getObject();

        // Check if the response contains an error
        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during VM deletion: " + responseObj.toString());
        }

        // For deletion, sometimes the response may not have a data field or it may be null
        // This is acceptable as the deletion operation may complete immediately
        if (responseObj.has("data") && !responseObj.isNull("data")) {
            return responseObj.getString("data");
        } else {
            // Return success indicator for immediate deletions
            return "VM deletion completed";
        }
    }

    /**
     * Execute a command on a VM using the guest agent
     * @param node The Proxmox node
     * @param vmid The VM ID
     * @param command The command to execute
     * @return The task ID for the command execution
     * @throws LoginException if authentication fails
     */
    public String executeGuestCommand(String node, Integer vmid, String command) throws LoginException {
        JSONObject responseObj = null;

        // Proxmox VE 8+ requires command to be passed as JSON array in the command parameter
        // Based on QEMU guest agent and Proxmox documentation, we need proper Windows command format
        String[] commandArray;
        if (command.toLowerCase().startsWith("dir") || command.toLowerCase().contains(":\\") ||
            command.toLowerCase().contains("curl.exe") ||
            command.toLowerCase().startsWith("powershell") || command.toLowerCase().startsWith("cmd") ||
            command.toLowerCase().startsWith("type") || command.toLowerCase().startsWith("copy") ||
            command.toLowerCase().startsWith("del") || command.toLowerCase().startsWith("move") ||
            command.toLowerCase().startsWith("echo") || command.toLowerCase().startsWith("set") ||
            command.toLowerCase().startsWith("ipconfig") || command.toLowerCase().startsWith("netstat")) {
            // Windows command - use proper Windows guest agent format
            if (command.toLowerCase().startsWith("powershell")) {
                // PowerShell command - use full path and proper arguments
                // Split all space-separated parts into individual array elements
                String psCommand;
                if (command.toLowerCase().startsWith("powershell.exe")) {
                    psCommand = command.substring(14).trim(); // Remove "powershell.exe "
                } else {
                    psCommand = command.substring(10).trim(); // Remove "powershell "
                }

                if (!psCommand.isEmpty()) {
                    String[] parts = psCommand.split("\\s+");
                    java.util.List<String> cmdList = new java.util.ArrayList<>();
                    cmdList.add("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe");
                    cmdList.add("--");
                    for (String part : parts) {
                        cmdList.add(part);
                    }
                    commandArray = cmdList.toArray(new String[0]);
                } else {
                    commandArray = new String[]{"C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"};
                }
            } else if (command.toLowerCase().startsWith("cmd")) {
                // cmd command - split all space-separated parts into individual array elements
                String cmdCommand;
                if (command.toLowerCase().startsWith("cmd.exe")) {
                    cmdCommand = command.substring(7).trim(); // Remove "cmd.exe "
                } else {
                    cmdCommand = command.substring(3).trim(); // Remove "cmd "
                }

                if (!cmdCommand.isEmpty()) {
                    String[] parts = cmdCommand.split("\\s+");
                    java.util.List<String> cmdList = new java.util.ArrayList<>();
                    cmdList.add("C:\\Windows\\System32\\cmd.exe");
                    cmdList.add("--");
                    for (String part : parts) {
                        cmdList.add(part);
                    }
                    commandArray = cmdList.toArray(new String[0]);
                } else {
                    commandArray = new String[]{"C:\\Windows\\System32\\cmd.exe"};
                }
            } else {
                // Regular Windows command - split all space-separated parts
                // QEMU guest agent format: ["cmd.exe", "--", "/c", "arg1", "arg2", ...]
                String sanitizedCommand = sanitizeWindowsCommand(command);
                String[] parts = sanitizedCommand.split("\\s+");
                java.util.List<String> cmdList = new java.util.ArrayList<>();
                cmdList.add("C:\\Windows\\System32\\cmd.exe");
                cmdList.add("--");
                cmdList.add("/c");
                for (String part : parts) {
                    cmdList.add(part);
                }
                commandArray = cmdList.toArray(new String[0]);
            }
        } else {
            // Unix/Linux command - split all space-separated parts
            // QEMU guest agent format: ["/bin/sh", "--", "-c", "arg1", "arg2", ...]
            String[] parts = command.split("\\s+");
            java.util.List<String> cmdList = new java.util.ArrayList<>();
            cmdList.add("/bin/sh");
            cmdList.add("--");
            cmdList.add("-c");
            for (String part : parts) {
                cmdList.add(part);
            }
            commandArray = cmdList.toArray(new String[0]);
        }

        // Build request with JSON array for command parameter (Proxmox VE 8+ format)
        // The command must be passed as a JSON array: {"command": ["cmd", "arg1", "arg2"]}
        kong.unirest.json.JSONArray jsonCommandArray = new kong.unirest.json.JSONArray();
        for (String part : commandArray) {
            jsonCommandArray.put(part);
        }

        // Debug logging to understand what we're sending
        LOGGER.log(Level.INFO, "Executing guest command on VM " + vmid);
        LOGGER.log(Level.FINE, "Original command: " + command);
        LOGGER.log(Level.FINE, "Command array: {0}", java.util.Arrays.toString(commandArray));
        LOGGER.log(Level.FINE, "JSON command array: {0}", jsonCommandArray.toString());

        // Create JSON body according to Proxmox 8 format
        kong.unirest.json.JSONObject requestBody = new kong.unirest.json.JSONObject();
        requestBody.put("command", jsonCommandArray);

        LOGGER.log(Level.FINE, "Request body JSON: {0}", requestBody.toString());

        HttpResponse<JsonNode> response = JSONResource(
            unirest.post(baseURL + "/nodes/" + node + "/qemu/" + vmid + "/agent/exec")
                .header("Content-Type", "application/json")
                .body(requestBody.toString())
        );

        responseObj = response.getBody().getObject();

        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during guest command execution: " + responseObj.toString());
        }

        if (!responseObj.has("data") || responseObj.isNull("data")) {
            // Check if there's a more specific error message
            String errorMsg = "Proxmox API returned null data for guest command execution.";
            if (responseObj.has("message")) {
                errorMsg += " Message: " + responseObj.getString("message");
            }
            errorMsg += " Full response: " + responseObj.toString();
            throw new RuntimeException(errorMsg);
        }

        // Handle different response formats - data can be string, number, or object
        Object dataObj = responseObj.get("data");
        LOGGER.log(Level.FINE, "Response data type: {0}, value: {1}", new Object[]{dataObj.getClass().getSimpleName(), dataObj.toString()});
        if (dataObj instanceof String) {
            return (String) dataObj;
        } else if (dataObj instanceof Number) {
            return String.valueOf(((Number) dataObj).longValue());
        } else if (dataObj instanceof kong.unirest.json.JSONObject) {
            // If data is an object, it might contain a pid field
            kong.unirest.json.JSONObject dataJsonObj = (kong.unirest.json.JSONObject) dataObj;
            if (dataJsonObj.has("pid")) {
                Object pidObj = dataJsonObj.get("pid");
                if (pidObj instanceof Number) {
                    return String.valueOf(((Number) pidObj).longValue());
                } else {
                    return pidObj.toString();
                }
            } else {
                return dataJsonObj.toString();
            }
        } else {
            return dataObj.toString();
        }
    }

    /**
     * Get the status of a guest agent command execution
     * @param node The Proxmox node
     * @param vmid The VM ID
     * @param pid The process ID returned from executeGuestCommand
     * @return JSONObject containing command execution status and result
     * @throws LoginException if authentication fails
     */
    public JSONObject getGuestCommandStatus(String node, Integer vmid, String pid) throws LoginException {
        HttpResponse<JsonNode> response = JSONResource(
            unirest.get(baseURL + "/nodes/" + node + "/qemu/" + vmid + "/agent/exec-status")
                .queryString("pid", pid)
        );

        JSONObject responseObj = response.getBody().getObject();

        if (responseObj.has("errors")) {
            throw new RuntimeException("Proxmox API error during guest command status check: " + responseObj.toString());
        }

        if (!responseObj.has("data") || responseObj.isNull("data")) {
            throw new RuntimeException("Proxmox API returned null data for guest command status. Response: " + responseObj.toString());
        }

        return responseObj.getJSONObject("data");
    }

    /**
     * Check if the guest agent is available on the VM
     * @param node The Proxmox node
     * @param vmid The VM ID
     * @return true if guest agent is available, false otherwise
     * @throws LoginException if authentication fails
     */
    public boolean isGuestAgentAvailable(String node, Integer vmid) throws LoginException {
        try {
            HttpResponse<JsonNode> response = JSONResource(
                unirest.get(baseURL + "/nodes/" + node + "/qemu/" + vmid + "/agent/ping")
            );

            JSONObject responseObj = response.getBody().getObject();

            // If we get a successful response, the agent is available
            return !responseObj.has("errors");
        } catch (Exception e) {
            // If any exception occurs, assume agent is not available
            return false;
        }
    }

    private String sanitizeWindowsCommand(String command) {
        if (command == null || command.trim().isEmpty()) {
            return command;
        }

        String sanitized = command.trim();

        // Fix common Windows command issues that cause syntax errors
        if (sanitized.matches("^dir\\s+[a-zA-Z]:\\\\?$")) {
            // Pattern like "dir c:\" or "dir c:" - remove trailing backslash if present
            sanitized = sanitized.replaceAll("\\\\+$", "");
        }

        // Handle other problematic trailing backslashes in file paths
        if (sanitized.endsWith("\\") && !sanitized.endsWith("\\\\")) {
            // Single trailing backslash (not escaped) can cause issues
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }

        return sanitized;
    }

    protected void finalize() {
        unirest.shutDown();
    }
}
