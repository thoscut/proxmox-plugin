package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import javax.security.auth.login.LoginException;
import java.util.HashMap;
import java.util.List;

import org.jenkinsci.plugins.proxmox.Datacenter;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class CloneVirtualMachineAdvanced extends ProxmoxBuildStep {

    private final String targetVmId;
    private final String cloneName;
    private final boolean fullClone;
    private final String snapshotName;
    private final boolean startAfterClone;
    private final int startupWaitSeconds;

    @DataBoundConstructor
    public CloneVirtualMachineAdvanced(String datacenterDescription, String datacenterNode, String vmId,
                                      String targetVmId, String cloneName, boolean fullClone,
                                      String snapshotName, boolean startAfterClone, int startupWaitSeconds) {
        super(datacenterDescription, datacenterNode, vmId);
        this.targetVmId = targetVmId;
        this.cloneName = cloneName;
        this.fullClone = fullClone;
        this.snapshotName = snapshotName;
        this.startAfterClone = startAfterClone;
        this.startupWaitSeconds = startupWaitSeconds;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, AbortException {

        String cloneType = fullClone ? "full clone" : "linked clone";
        logInfo(listener, "Creating " + cloneType + " of VM " + vmId + " to new VM " + targetVmId + " (" + cloneName + ")");

        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer sourceVmId = parseVmId();
            Integer targetVmIdInt = parseTargetVmId();

            // Check if target VM ID already exists
            HashMap<String, Integer> existingVms = proxmoxApi.getQemuMachines(datacenterNode);
            if (existingVms.containsValue(targetVmIdInt)) {
                throw new AbortException("Target VM ID " + targetVmId + " already exists");
            }

            // Validate snapshot exists if specified
            if (snapshotName != null && !snapshotName.isEmpty()) {
                List<String> snapshots = proxmoxApi.getQemuMachineSnapshots(datacenterNode, sourceVmId);
                if (!snapshots.contains(snapshotName)) {
                    throw new AbortException("Snapshot '" + snapshotName + "' not found on source VM " + vmId);
                }
                logInfo(listener, "Using snapshot: " + snapshotName);
            }

            // Clone the VM
            String taskId = proxmoxApi.cloneQemuMachine(datacenterNode, sourceVmId, targetVmIdInt,
                                                      cloneName, fullClone, snapshotName);
            logInfo(listener, "Started " + cloneType + ", task ID: " + taskId);

            // Wait for cloning to complete
            logInfo(listener, "Waiting for cloning to complete...");
            try {
                proxmoxApi.waitForTaskToFinish(datacenterNode, taskId);
                logInfo(listener, "Cloning completed successfully");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AbortException("Cloning was interrupted");
            }

            // Verify clone was created
            HashMap<String, Integer> updatedVms = proxmoxApi.getQemuMachines(datacenterNode);
            if (!updatedVms.containsValue(targetVmIdInt)) {
                throw new AbortException("Cloned VM was not found after creation");
            }

            // Start VM if requested
            if (startAfterClone) {
                logInfo(listener, "Starting cloned VM");
                String startTaskId = proxmoxApi.startQemuMachine(datacenterNode, targetVmIdInt);
                logInfo(listener, "VM start task ID: " + startTaskId);

                // Wait for startup if specified
                if (startupWaitSeconds > 0) {
                    logInfo(listener, "Waiting " + startupWaitSeconds + " seconds for VM startup");
                    Thread.sleep(startupWaitSeconds * 1000L);
                }

                // Verify VM is running
                if (proxmoxApi.isQemuMachineRunning(datacenterNode, targetVmIdInt)) {
                    logInfo(listener, "Cloned VM started successfully");
                } else {
                    logError(listener, "Cloned VM failed to start", null);
                    return false;
                }
            }

            logInfo(listener, "VM cloned successfully: " + cloneName + " (ID: " + targetVmId + ")");
            return true;

        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to clone VM", e);
            throw new AbortException("Failed to clone VM: " + e.getMessage());
        }
    }

    private Integer parseTargetVmId() throws AbortException {
        try {
            return Integer.parseInt(targetVmId);
        } catch (NumberFormatException e) {
            throw new AbortException("Invalid target VM ID: " + targetVmId);
        }
    }

    public String getTargetVmId() {
        return targetVmId;
    }

    public String getCloneName() {
        return cloneName;
    }

    public boolean getFullClone() {
        return fullClone;
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public boolean getStartAfterClone() {
        return startAfterClone;
    }

    public int getStartupWaitSeconds() {
        return startupWaitSeconds;
    }

    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "Proxmox: Clone Virtual Machine (Advanced)";
        }

        public FormValidation doCheckTargetVmId(@QueryParameter String value) {
            try {
                int vmId = Integer.parseInt(value);
                if (vmId < 1) {
                    return FormValidation.error("VM ID must be positive");
                }
                if (vmId > 999999) {
                    return FormValidation.warning("VM ID is very large");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("VM ID must be a valid integer");
            }
        }

        public FormValidation doCheckCloneName(@QueryParameter String value) {
            if (value == null || value.trim().isEmpty()) {
                return FormValidation.error("Clone name is required");
            }
            if (value.length() > 63) {
                return FormValidation.error("Clone name too long (max 63 characters)");
            }
            if (!value.matches("^[a-zA-Z0-9][a-zA-Z0-9-]*$")) {
                return FormValidation.error("Invalid clone name format (alphanumeric and hyphens only, must start with alphanumeric)");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckStartupWaitSeconds(@QueryParameter int value) {
            if (value < 0) {
                return FormValidation.error("Wait time cannot be negative");
            }
            if (value > 600) {
                return FormValidation.warning("Long wait time (over 10 minutes)");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillSnapshotNameItems(@QueryParameter String datacenterDescription,
                                                   @QueryParameter String datacenterNode,
                                                   @QueryParameter String vmId) {
            ListBoxModel items = new ListBoxModel();
            items.add("[None - Clone current state]", "");

            if (datacenterDescription != null && !datacenterDescription.isEmpty() &&
                datacenterNode != null && !datacenterNode.isEmpty() &&
                vmId != null && !vmId.isEmpty()) {

                try {
                    Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
                    if (datacenter != null) {
                        Integer vmIdInt = Integer.parseInt(vmId);
                        List<String> snapshots = datacenter.getQemuMachineSnapshots(datacenterNode, vmIdInt);
                        for (String snapshot : snapshots) {
                            items.add(snapshot);
                        }
                    }
                } catch (NumberFormatException e) {
                    // Invalid VM ID, return list with just default option
                }
            }
            return items;
        }

        private Datacenter getDatacenterByDescription(String description) {
            for (hudson.slaves.Cloud cloud : jenkins.model.Jenkins.get().clouds) {
                if (cloud instanceof Datacenter) {
                    Datacenter datacenter = (Datacenter) cloud;
                    if (datacenter.getDatacenterDescription().equals(description)) {
                        return datacenter;
                    }
                }
            }
            return null;
        }
    }
}