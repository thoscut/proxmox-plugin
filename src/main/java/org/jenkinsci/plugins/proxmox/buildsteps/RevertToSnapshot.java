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
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class RevertToSnapshot extends ProxmoxBuildStep {

    private final String snapshotName;
    private final boolean startAfterRevert;
    
    @DataBoundConstructor
    public RevertToSnapshot(String datacenterDescription, String datacenterNode, String vmId,
                           String snapshotName, boolean startAfterRevert) {
        super(datacenterDescription, datacenterNode, vmId);
        this.snapshotName = snapshotName;
        this.startAfterRevert = startAfterRevert;
    }
    
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) 
            throws InterruptedException, AbortException {
        
        logInfo(listener, "Reverting VM " + vmId + " to snapshot: " + snapshotName);
        
        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();
            
            // Verify snapshot exists
            List<String> snapshots = proxmoxApi.getQemuMachineSnapshots(datacenterNode, vmIdInt);
            if (!snapshots.contains(snapshotName)) {
                throw new AbortException("Snapshot '" + snapshotName + "' not found on VM " + vmId);
            }
            
            // Stop VM if running (required for snapshot revert)
            if (proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "Stopping VM before snapshot revert");
                String stopTaskId = proxmoxApi.stopQemuMachine(datacenterNode, vmIdInt);
                
                // Wait for VM to stop
                int maxWait = 30; // Wait up to 30 seconds
                for (int i = 0; i < maxWait; i++) {
                    Thread.sleep(1000);
                    if (!proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                        break;
                    }
                    if (i == maxWait - 1) {
                        throw new AbortException("VM did not stop within " + maxWait + " seconds");
                    }
                }
                logInfo(listener, "VM stopped successfully");
            }
            
            // Revert to snapshot
            String rollbackTaskId = proxmoxApi.rollbackQemuMachineSnapshot(datacenterNode, vmIdInt, snapshotName);
            logInfo(listener, "Started snapshot revert, task ID: " + rollbackTaskId);
            
            // Start VM if requested
            if (startAfterRevert) {
                logInfo(listener, "Starting VM after snapshot revert");
                String startTaskId = proxmoxApi.startQemuMachine(datacenterNode, vmIdInt);
                logInfo(listener, "VM start task ID: " + startTaskId);
                
                // Give VM time to start
                Thread.sleep(5000);
                
                if (proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                    logInfo(listener, "VM started successfully after snapshot revert");
                } else {
                    logError(listener, "VM failed to start after snapshot revert", null);
                    return false;
                }
            }
            
            logInfo(listener, "Successfully reverted VM " + vmId + " to snapshot: " + snapshotName);
            return true;
            
        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to revert to snapshot", e);
            throw new AbortException("Failed to revert to snapshot: " + e.getMessage());
        }
    }
    
    public String getSnapshotName() {
        return snapshotName;
    }
    
    public boolean getStartAfterRevert() {
        return startAfterRevert;
    }
    
    @Extension
    @Symbol("proxmoxRevertToSnapshot")
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {
        
        @Override
        public String getDisplayName() {
            return "Proxmox: Revert to Snapshot";
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