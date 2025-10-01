package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;

import javax.security.auth.login.LoginException;

import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class RunCommand extends ProxmoxBuildStep {

    private final String command;
    private final int timeoutSeconds;
    private final boolean waitForCompletion;
    private final boolean failOnError;

    @DataBoundConstructor
    public RunCommand(String datacenterDescription, String datacenterNode, String vmId,
                     String command, int timeoutSeconds, boolean waitForCompletion, boolean failOnError) {
        super(datacenterDescription, datacenterNode, vmId);
        this.command = command;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : 300; // Default 5 minutes
        this.waitForCompletion = waitForCompletion;
        this.failOnError = failOnError;
    }

    public String getCommand() {
        return command;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public boolean getWaitForCompletion() {
        return waitForCompletion;
    }

    public boolean getFailOnError() {
        return failOnError;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, AbortException {

        if (command == null || command.trim().isEmpty()) {
            logInfo(listener, "[ERROR] Command cannot be empty");
            return false;
        }

        logInfo(listener, "Executing command on VM " + vmId + " on node " + datacenterNode);
        logInfo(listener, "Command: " + command);

        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();

            // Check if guest agent is available
            if (!proxmoxApi.isGuestAgentAvailable(datacenterNode, vmIdInt)) {
                String errorMsg = "Guest agent is not available on VM " + vmId + ". Make sure the guest agent is installed and running.";
                logInfo(listener, "[ERROR] " + errorMsg);
                if (failOnError) {
                    throw new AbortException(errorMsg);
                }
                return false;
            }

            // Execute the command
            String pid = proxmoxApi.executeGuestCommand(datacenterNode, vmIdInt, command);
            logInfo(listener, "Command execution started with PID: " + pid);

            if (!waitForCompletion) {
                logInfo(listener, "Command execution initiated successfully (not waiting for completion)");
                return true;
            }

            // Wait for command completion
            logInfo(listener, "Waiting for command completion (timeout: " + timeoutSeconds + " seconds)");

            long startTime = System.currentTimeMillis();
            long timeoutMs = timeoutSeconds * 1000L;
            JSONObject status = null;
            boolean completed = false;

            while (!completed && (System.currentTimeMillis() - startTime) < timeoutMs) {
                Thread.sleep(2000); // Check every 2 seconds

                status = proxmoxApi.getGuestCommandStatus(datacenterNode, vmIdInt, pid);

                if (status.has("exited") && status.getBoolean("exited")) {
                    completed = true;
                } else {
                    logInfo(listener, "Command still running...");
                }
            }

            if (!completed) {
                String errorMsg = "Command execution timed out after " + timeoutSeconds + " seconds";
                logInfo(listener, "[ERROR] " + errorMsg);
                if (failOnError) {
                    throw new AbortException(errorMsg);
                }
                return false;
            }

            // Process results
            int exitCode = status.optInt("exitcode", -1);
            String stdout = status.optString("out-data", "");
            String stderr = status.optString("err-data", "");

            logInfo(listener, "Command completed with exit code: " + exitCode);

            if (!stdout.isEmpty()) {
                logInfo(listener, "STDOUT:");
                logInfo(listener, stdout);
            }

            if (!stderr.isEmpty()) {
                logInfo(listener, "STDERR:");
                logInfo(listener, stderr);
            }

            if (exitCode != 0) {
                String errorMsg = "Command failed with exit code: " + exitCode;
                logInfo(listener, "[ERROR] " + errorMsg);
                if (failOnError) {
                    throw new AbortException(errorMsg);
                }
                return false;
            }

            logInfo(listener, "Command executed successfully");
            return true;

        } catch (LoginException e) {
            String errorMsg = "Login failed: " + e.getMessage();
            logError(listener, errorMsg, e);
            throw new AbortException(errorMsg);
        } catch (Exception e) {
            String errorMsg = "Command execution failed: " + e.getMessage();
            logError(listener, errorMsg, e);
            if (failOnError) {
                throw new AbortException(errorMsg);
            }
            return false;
        }
    }

    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "Run Command on Proxmox VM (Guest Agent)";
        }

        @POST
        public FormValidation doCheckCommand(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Command cannot be empty");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckTimeoutSeconds(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            try {
                int timeout = Integer.parseInt(value);
                if (timeout <= 0) {
                    return FormValidation.error("Timeout must be greater than 0");
                }
                if (timeout > 3600) {
                    return FormValidation.warning("Timeout is very long (> 1 hour). Consider using a shorter timeout.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Please enter a valid number");
            }
        }

        @POST
        public FormValidation doTestGuestAgent(
                @QueryParameter String datacenterDescription,
                @QueryParameter String datacenterNode,
                @QueryParameter String vmId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (datacenterDescription == null || datacenterDescription.trim().isEmpty()) {
                return FormValidation.error("Please select a datacenter");
            }
            if (datacenterNode == null || datacenterNode.trim().isEmpty()) {
                return FormValidation.error("Please select a node");
            }
            if (vmId == null || vmId.trim().isEmpty()) {
                return FormValidation.error("Please enter a VM ID");
            }

            try {
                // Find datacenter
                org.jenkinsci.plugins.proxmox.Datacenter datacenter = null;
                for (hudson.slaves.Cloud cloud : Jenkins.get().clouds) {
                    if (cloud instanceof org.jenkinsci.plugins.proxmox.Datacenter) {
                        org.jenkinsci.plugins.proxmox.Datacenter dc = (org.jenkinsci.plugins.proxmox.Datacenter) cloud;
                        if (datacenter.getDatacenterDescription().equals(datacenterDescription)) {
                            datacenter = dc;
                            break;
                        }
                    }
                }

                if (datacenter == null) {
                    return FormValidation.error("Datacenter not found: " + datacenterDescription);
                }

                Connector proxmoxApi = datacenter.proxmoxInstance();
                Integer vmIdInt = Integer.parseInt(vmId);

                // Check if VM exists and is running
                if (!proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                    return FormValidation.warning("VM " + vmId + " is not running. Guest agent requires a running VM.");
                }

                // Test guest agent
                if (proxmoxApi.isGuestAgentAvailable(datacenterNode, vmIdInt)) {
                    return FormValidation.ok("Guest agent is available and responding");
                } else {
                    return FormValidation.error("Guest agent is not available. Make sure the guest agent is installed and running in the VM.");
                }

            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid VM ID: " + vmId);
            } catch (Exception e) {
                return FormValidation.error("Test failed: " + e.getMessage());
            }
        }
    }
}