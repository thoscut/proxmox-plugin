package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import javax.security.auth.login.LoginException;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;

public class CreateSnapshot extends ProxmoxBuildStep {

    private final String snapshotName;
    private final String description;
    private final boolean includeRam;

    @DataBoundConstructor
    public CreateSnapshot(String datacenterDescription, String datacenterNode, String vmId,
                         String snapshotName, String description, boolean includeRam) {
        super(datacenterDescription, datacenterNode, vmId);
        this.snapshotName = snapshotName;
        this.description = description;
        this.includeRam = includeRam;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, AbortException {

        logInfo(listener, "Creating snapshot '" + snapshotName + "' for VM " + vmId);

        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();

            // Check if snapshot name already exists
            if (proxmoxApi.getQemuMachineSnapshots(datacenterNode, vmIdInt).contains(snapshotName)) {
                throw new AbortException("Snapshot '" + snapshotName + "' already exists on VM " + vmId);
            }

            // Create the snapshot
            String taskId = proxmoxApi.createQemuMachineSnapshot(datacenterNode, vmIdInt, snapshotName, description, includeRam);
            logInfo(listener, "Started snapshot creation, task ID: " + taskId);

            // Wait for the task to complete
            proxmoxApi.waitForTaskToFinish(datacenterNode, taskId);
            logInfo(listener, "Snapshot creation task completed");

            // Verify snapshot was created
            if (proxmoxApi.getQemuMachineSnapshots(datacenterNode, vmIdInt).contains(snapshotName)) {
                logInfo(listener, "Successfully created snapshot '" + snapshotName + "' for VM " + vmId);
                return true;
            } else {
                logError(listener, "Snapshot was not found after creation", null);
                return false;
            }

        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to create snapshot", e);
            throw new AbortException("Failed to create snapshot: " + e.getMessage());
        }
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public String getDescription() {
        return description;
    }

    public boolean getIncludeRam() {
        return includeRam;
    }

    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "Proxmox: Create Snapshot";
        }
    }
}