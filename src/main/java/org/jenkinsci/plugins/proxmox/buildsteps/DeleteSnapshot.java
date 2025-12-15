package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.ListBoxModel;

import javax.security.auth.login.LoginException;
import java.util.List;

import org.jenkinsci.plugins.proxmox.Datacenter;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class DeleteSnapshot extends ProxmoxBuildStep {

    private final String snapshotName;

    @DataBoundConstructor
    public DeleteSnapshot(String datacenterDescription, String datacenterNode, String vmId,
                         String snapshotName) {
        super(datacenterDescription, datacenterNode, vmId);
        this.snapshotName = snapshotName;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, AbortException {

        logInfo(listener, "Deleting snapshot '" + snapshotName + "' from VM " + vmId);

        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();

            // Check if snapshot exists
            List<String> snapshots = proxmoxApi.getQemuMachineSnapshots(datacenterNode, vmIdInt);
            if (!snapshots.contains(snapshotName)) {
                throw new AbortException("Snapshot '" + snapshotName + "' not found on VM " + vmId);
            }

            // Delete the snapshot
            String taskId = proxmoxApi.deleteQemuMachineSnapshot(datacenterNode, vmIdInt, snapshotName);
            logInfo(listener, "Started snapshot deletion, task ID: " + taskId);

            // Wait for the task to complete
            proxmoxApi.waitForTaskToFinish(datacenterNode, taskId);
            logInfo(listener, "Snapshot deletion task completed");

            // Verify snapshot was deleted
            List<String> remainingSnapshots = proxmoxApi.getQemuMachineSnapshots(datacenterNode, vmIdInt);
            if (!remainingSnapshots.contains(snapshotName)) {
                logInfo(listener, "Successfully deleted snapshot '" + snapshotName + "' from VM " + vmId);
                return true;
            } else {
                logError(listener, "Snapshot still exists after deletion", null);
                return false;
            }

        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to delete snapshot", e);
            throw new AbortException("Failed to delete snapshot: " + e.getMessage());
        }
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "Delete Snapshot";
        }

        public ListBoxModel doFillSnapshotNameItems(@QueryParameter String datacenterDescription,
                                                   @QueryParameter String datacenterNode,
                                                   @QueryParameter String vmId) {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");

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
                    // Invalid VM ID, return empty list
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