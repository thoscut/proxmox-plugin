package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import hudson.util.FormValidation;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Launcher that connects to Jenkins agents via QEMU Guest Agent commands.
 * This launcher executes the Jenkins agent startup command directly on the VM
 * using the Proxmox QEMU Guest Agent functionality, then waits for the agent
 * to connect back via JNLP.
 */
public class QemuGuestAgentLauncher extends JNLPLauncher {

    private static final Logger LOGGER = Logger.getLogger(QemuGuestAgentLauncher.class.getName());

    private final String agentCommand;
    private final int connectionTimeoutSeconds;
    private final int maxRetries;
    private final boolean waitForAgentReady;
    private final boolean useWebSocket;
    private final String workDir;
    private final boolean curlSslNoRevoke;
    private final boolean useDirect;

    @DataBoundConstructor
    public QemuGuestAgentLauncher(String agentCommand, int connectionTimeoutSeconds, int maxRetries, boolean waitForAgentReady, boolean useWebSocket, String workDir, boolean curlSslNoRevoke, boolean useDirect) {
        this.agentCommand = agentCommand;
        this.connectionTimeoutSeconds = connectionTimeoutSeconds > 0 ? connectionTimeoutSeconds : 60;
        this.maxRetries = maxRetries > 0 ? maxRetries : 3;
        this.waitForAgentReady = waitForAgentReady;
        this.useWebSocket = useWebSocket;
        this.workDir = workDir;
        this.curlSslNoRevoke = curlSslNoRevoke;
        this.useDirect = useDirect;
    }

    private String getDefaultAgentCommand() {
        // Default command includes downloading agent.jar first, then running it
        // This matches what Jenkins displays in the UI for inbound agents
        // Uses a secret file instead of command line parameter for better security
        //
        // Note: The Connector.executeGuestCommand() automatically detects the OS based on
        // command patterns and wraps the command appropriately:
        // - Windows commands (starting with echo, dir, cmd, etc.) are wrapped with: cmd.exe /c "..."
        // - Other commands are wrapped with: /bin/sh -c "..."
        //
        // By default, we generate Windows commands (starting with echo). For Unix/Linux VMs,
        // provide a custom command via the agentCommand field that starts with Unix commands
        // (printf, sh, bash, etc.) or the user can provide explicit OS-specific commands.
        return getDefaultAgentCommandWindows();
    }

    private String getDefaultAgentCommandUnix() {
        // Unix/Linux command using && operators
        // IMPORTANT: Add spaces around operators so they split correctly into array elements
        StringBuilder cmd = new StringBuilder();

        // Create secret file first (more secure than passing secret on command line)
        // Use printf instead of echo to avoid issues with special characters
        // Add spaces around > and && for proper splitting
        cmd.append("printf '%s' {SECRET} > secret-file && ");

        // Download agent.jar from Jenkins
        cmd.append("curl -sO");
        if (curlSslNoRevoke) {
            cmd.append(" --ssl-no-revoke");
        }
        cmd.append(" {JENKINS_URL}jnlpJars/agent.jar && ");

        // Run the agent
        cmd.append("java -jar agent.jar");

        // -url and -direct are mutually exclusive
        if (useDirect) {
            // Use -direct mode with instance identity passed directly
            cmd.append(" -direct {DIRECT_CONNECTION}");
            cmd.append(" -secret @secret-file");
            // -instanceIdentity value passed directly (not via file)
            cmd.append(" -instanceIdentity {INSTANCE_IDENTITY}");
            // -direct does not support -webSocket
        } else {
            cmd.append(" -url {JENKINS_URL}");
            cmd.append(" -secret @secret-file");
            if (useWebSocket) {
                cmd.append(" -webSocket");
            }
        }

        cmd.append(" -name {COMPUTER_NAME}");

        if (workDir != null && !workDir.trim().isEmpty()) {
            cmd.append(" -workDir ").append(workDir);
        }
        return cmd.toString();
    }

    private String getDefaultAgentCommandWindows() {
        // Windows command using & operators
        // IMPORTANT: Add spaces around operators so they split correctly into array elements
        StringBuilder cmd = new StringBuilder();

        // Download agent.jar from Jenkins (use curl.exe on Windows)
        cmd.append("curl.exe -sO");
        if (curlSslNoRevoke) {
            cmd.append(" --ssl-no-revoke");
        }
        cmd.append(" {JENKINS_URL}jnlpJars/agent.jar & ");

        // Run the agent
        cmd.append("java -jar agent.jar");

        // -url and -direct are mutually exclusive
        if (useDirect) {
            // Use -direct mode with instance identity passed directly
            cmd.append(" -direct {DIRECT_CONNECTION}");
            cmd.append(" -secret {SECRET}");
            // -instanceIdentity value passed directly (not via file)
            cmd.append(" -instanceIdentity {INSTANCE_IDENTITY}");
            // -direct does not support -webSocket
        } else {
            cmd.append(" -url {JENKINS_URL}");
            cmd.append(" -secret {SECRET}");
            if (useWebSocket) {
                cmd.append(" -webSocket");
            }
        }

        cmd.append(" -name {COMPUTER_NAME}");

        if (workDir != null && !workDir.trim().isEmpty()) {
            cmd.append(" -workDir ").append(workDir);
        }
        return cmd.toString();
    }

    public String getAgentCommand() {
        return agentCommand;
    }

    public int getConnectionTimeoutSeconds() {
        return connectionTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public boolean getWaitForAgentReady() {
        return waitForAgentReady;
    }

    public boolean getUseWebSocket() {
        return useWebSocket;
    }

    public String getWorkDir() {
        return workDir;
    }

    public boolean getCurlSslNoRevoke() {
        return curlSslNoRevoke;
    }

    public boolean getUseDirect() {
        return useDirect;
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) {
        listener.getLogger().println("Starting Jenkins agent via QEMU Guest Agent...");

        VirtualMachineSlave slave = (VirtualMachineSlave) computer.getNode();
        if (slave == null) {
            LOGGER.log(Level.SEVERE, "Could not get VirtualMachineSlave for computer: " + computer.getName());
            return;
        }

        String datacenterDescription = slave.getDatacenterDescription();
        String datacenterNode = slave.getDatacenterNode();
        Integer vmId = slave.getVirtualMachineId();

        try {
            // Find the datacenter instance
            Datacenter datacenter = findDatacenterInstance(datacenterDescription);
            Connector proxmoxApi = datacenter.proxmoxInstance();

            // Build the agent command with proper variable substitutions
            String expandedCommand = expandAgentCommand(computer, slave);
            listener.getLogger().println("Executing agent command: " + expandedCommand);

            // Execute the agent startup command via guest agent
            String pid = executeAgentCommand(proxmoxApi, datacenterNode, vmId, expandedCommand, listener);

            if (waitForAgentReady) {
                listener.getLogger().println("Agent command started with PID: " + pid + ". Waiting for agent to become ready...");

                // Poll for command output while waiting for agent connection
                pollCommandOutput(proxmoxApi, datacenterNode, vmId, pid, listener);

                waitForAgentConnection(computer, listener);
            } else {
                listener.getLogger().println("Agent command started with PID: " + pid + ". Not waiting for connection.");
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.SEVERE, "Agent launch was interrupted", e);
            listener.getLogger().println("ERROR: Agent launch was interrupted: " + e.getMessage());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to launch agent via guest agent", e);
            listener.getLogger().println("ERROR: Failed to launch agent via guest agent: " + e.getMessage());
        }
    }

    private String expandAgentCommand(SlaveComputer computer, VirtualMachineSlave slave) {
        // Use custom command or generate default
        String command = (agentCommand != null && !agentCommand.trim().isEmpty())
            ? agentCommand
            : getDefaultAgentCommand();

        Jenkins jenkins = Jenkins.get();

        // Replace common variables
        command = command.replace("{JENKINS_URL}", jenkins.getRootUrl());
        command = command.replace("{COMPUTER_NAME}", computer.getName());
        command = command.replace("{SECRET}", computer.getJnlpMac());
        command = command.replace("{VM_ID}", slave.getVirtualMachineId().toString());

        // Replace direct connection URL (hostname:port for TCP agent listener)
        // This uses the TCP port configured in Jenkins security settings for inbound agents
        if (command.contains("{DIRECT_CONNECTION}")) {
            String directConnection = getDirectConnectionUrl(jenkins);
            command = command.replace("{DIRECT_CONNECTION}", directConnection);
        }

        // Replace instance identity with the actual Jenkins instance identity public key
        if (command.contains("{INSTANCE_IDENTITY}")) {
            String instanceIdentity = getInstanceIdentity();
            command = command.replace("{INSTANCE_IDENTITY}", instanceIdentity);
        }

        return command;
    }

    private String getDirectConnectionUrl(Jenkins jenkins) {
        // Get the Jenkins URL and extract hostname
        String jenkinsUrl = jenkins.getRootUrl();
        if (jenkinsUrl == null) {
            jenkinsUrl = "http://localhost:8080/";
        }

        // Extract hostname from Jenkins URL
        String hostname;
        try {
            java.net.URL url = new java.net.URL(jenkinsUrl);
            hostname = url.getHost();
        } catch (Exception e) {
            hostname = "localhost";
        }

        // Get the TCP port for inbound agents from Jenkins settings
        // This is configured in Manage Jenkins > Security > TCP port for inbound agents
        int slaveAgentPort = 50000; // Default port
        if (jenkins.getTcpSlaveAgentListener() != null) {
            slaveAgentPort = jenkins.getTcpSlaveAgentListener().getAdvertisedPort();
        }

        return hostname + ":" + slaveAgentPort;
    }

    private String getInstanceIdentity() {
        try {
            // Get Jenkins instance identity - now that we have the dependency, we can use it directly
            org.jenkinsci.main.modules.instance_identity.InstanceIdentity identity =
                org.jenkinsci.main.modules.instance_identity.InstanceIdentity.get();

            // Get the public key
            java.security.interfaces.RSAPublicKey publicKey = identity.getPublic();

            if (publicKey == null) {
                throw new IllegalStateException("Jenkins instance identity public key is not available");
            }

            // Encode the public key to base64
            byte[] encoded = publicKey.getEncoded();
            return java.util.Base64.getEncoder().encodeToString(encoded);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to retrieve instance identity. Direct mode requires Jenkins instance identity.", e);
            throw new RuntimeException("Cannot retrieve Jenkins instance identity required for direct mode connection", e);
        }
    }

    private String executeAgentCommand(Connector proxmoxApi, String node, Integer vmId, String command, TaskListener listener)
            throws LoginException, InterruptedException {

        int attempts = 0;
        Exception lastException = null;

        while (attempts < maxRetries) {
            attempts++;
            try {
                listener.getLogger().println("Attempt " + attempts + "/" + maxRetries + " to execute agent command");

                // Execute the command via guest agent
                String pid = proxmoxApi.executeGuestCommand(node, vmId, command);
                listener.getLogger().println("Guest agent command executed successfully with PID: " + pid);

                return pid;

            } catch (Exception e) {
                lastException = e;
                listener.getLogger().println("Attempt " + attempts + " failed: " + e.getMessage());

                if (attempts < maxRetries) {
                    listener.getLogger().println("Waiting 5 seconds before retry...");
                    Thread.sleep(5000);
                }
            }
        }

        throw new RuntimeException("Failed to execute agent command after " + maxRetries + " attempts. Last error: " +
                                 (lastException != null ? lastException.getMessage() : "Unknown error"));
    }

    private void pollCommandOutput(Connector proxmoxApi, String node, Integer vmId, String pid, TaskListener listener)
            throws InterruptedException {
        listener.getLogger().println("Polling for command output...");

        // Poll for up to 10 seconds (5 attempts with 2 second intervals)
        int maxPolls = 5;
        for (int i = 0; i < maxPolls; i++) {
            try {
                Thread.sleep(2000); // Wait 2 seconds between polls

                JSONObject status = proxmoxApi.getGuestCommandStatus(node, vmId, pid);

                if (status != null) {
                    logCommandStatus(status, listener);

                    // If command has exited, stop polling
                    // Check if command has exited - "exited" can be boolean or integer (0/1)
                    boolean hasExited = false;
                    if (status.has("exited")) {
                        Object exitedObj = status.get("exited");
                        if (exitedObj instanceof Boolean) {
                            hasExited = (Boolean) exitedObj;
                        } else if (exitedObj instanceof Number) {
                            hasExited = ((Number) exitedObj).intValue() != 0;
                        }
                    }

                    if (hasExited) {
                        listener.getLogger().println("Command has completed, stopping output poll.");
                        break;
                    }
                }
            } catch (Exception e) {
                listener.getLogger().println("Failed to retrieve command status (poll " + (i+1) + "/" + maxPolls + "): " + e.getMessage());
                LOGGER.log(Level.WARNING, "Failed to retrieve command status during polling", e);
            }
        }
    }

    private void logCommandStatus(JSONObject status, TaskListener listener) {
        if (status == null) {
            return;
        }

        listener.getLogger().println("=== Guest Agent Command Status ===");

        // Debug: log all available fields in the status
        LOGGER.log(Level.FINE, "Status JSON: {0}", status.toString());

        // Check if command has exited - "exited" can be boolean or integer (0/1)
        boolean hasExited = false;
        if (status.has("exited")) {
            Object exitedObj = status.get("exited");
            if (exitedObj instanceof Boolean) {
                hasExited = (Boolean) exitedObj;
            } else if (exitedObj instanceof Number) {
                hasExited = ((Number) exitedObj).intValue() != 0;
            }
        }

        if (hasExited) {
            listener.getLogger().println("Command has exited");

            if (status.has("exitcode")) {
                int exitCode = status.getInt("exitcode");
                listener.getLogger().println("Exit code: " + exitCode);
            }
        } else {
            listener.getLogger().println("Command is still running");
        }

        // Log stdout if available (check both "out-data" and "stdout")
        boolean hasOutput = false;
        if (status.has("out-data") && !status.isNull("out-data")) {
            String stdout = status.getString("out-data");
            if (!stdout.trim().isEmpty()) {
                listener.getLogger().println("Standard output:");
                listener.getLogger().println(stdout);
                hasOutput = true;
            }
        } else if (status.has("stdout") && !status.isNull("stdout")) {
            String stdout = status.getString("stdout");
            if (!stdout.trim().isEmpty()) {
                listener.getLogger().println("Standard output:");
                listener.getLogger().println(stdout);
                hasOutput = true;
            }
        }

        // Log stderr if available (check both "err-data" and "stderr")
        if (status.has("err-data") && !status.isNull("err-data")) {
            String stderr = status.getString("err-data");
            if (!stderr.trim().isEmpty()) {
                listener.getLogger().println("Standard error:");
                listener.getLogger().println(stderr);
                hasOutput = true;
            }
        } else if (status.has("stderr") && !status.isNull("stderr")) {
            String stderr = status.getString("stderr");
            if (!stderr.trim().isEmpty()) {
                listener.getLogger().println("Standard error:");
                listener.getLogger().println(stderr);
                hasOutput = true;
            }
        }

        if (!hasOutput && !hasExited) {
            listener.getLogger().println("(No output available yet)");
        }

        listener.getLogger().println("=================================");
    }

    private void waitForAgentConnection(SlaveComputer computer, TaskListener listener) throws InterruptedException {
        listener.getLogger().println("Waiting up to " + connectionTimeoutSeconds + " seconds for agent to connect...");

        long startTime = System.currentTimeMillis();
        long timeoutMs = connectionTimeoutSeconds * 1000L;

        while ((System.currentTimeMillis() - startTime) < timeoutMs) {
            if (computer.isOnline()) {
                listener.getLogger().println("Agent connected successfully!");
                return;
            }

            Thread.sleep(2000); // Check every 2 seconds
        }

        listener.getLogger().println("Warning: Agent did not connect within timeout period");
    }

    private Datacenter findDatacenterInstance(String datacenterDescription) throws RuntimeException {
        for (hudson.slaves.Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof Datacenter) {
                Datacenter datacenter = (Datacenter) cloud;
                if (datacenter.getDatacenterDescription().equals(datacenterDescription)) {
                    return datacenter;
                }
            }
        }
        throw new RuntimeException("Could not find Proxmox datacenter instance: " + datacenterDescription);
    }

    @Override
    public boolean isLaunchSupported() {
        return true;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ComputerLauncher> {

        @Override
        public String getDisplayName() {
            return "Launch agent via QEMU Guest Agent";
        }

        public FormValidation doCheckAgentCommand(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.warning("Agent command should be specified. A default command will be used if empty.");
            }

            // Check for required variables
            if (!value.contains("{JENKINS_URL}") && !value.contains("jenkins-agent.jnlp")) {
                return FormValidation.warning("Command should typically contain {JENKINS_URL} or reference jenkins-agent.jnlp");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckConnectionTimeoutSeconds(@QueryParameter String value) {
            try {
                int timeout = Integer.parseInt(value);
                if (timeout <= 0) {
                    return FormValidation.error("Connection timeout must be a positive number");
                }
                if (timeout > 600) {
                    return FormValidation.warning("Very long timeout specified. Consider using a shorter value.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Connection timeout must be a valid number");
            }
        }

        public FormValidation doCheckMaxRetries(@QueryParameter String value) {
            try {
                int retries = Integer.parseInt(value);
                if (retries <= 0) {
                    return FormValidation.error("Max retries must be a positive number");
                }
                if (retries > 10) {
                    return FormValidation.warning("Very high retry count specified. Consider using a lower value.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Max retries must be a valid number");
            }
        }

        public FormValidation doCheckUseDirect(@QueryParameter boolean useDirect,
                                               @QueryParameter boolean useWebSocket) {
            if (useDirect && useWebSocket) {
                return FormValidation.error("Direct mode is not compatible with WebSocket. Please disable WebSocket when using Direct mode.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckUseWebSocket(@QueryParameter boolean useDirect,
                                                   @QueryParameter boolean useWebSocket) {
            if (useDirect && useWebSocket) {
                return FormValidation.error("WebSocket is not compatible with Direct mode. Please disable Direct mode to use WebSocket.");
            }
            return FormValidation.ok();
        }
    }
}