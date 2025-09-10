package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.FormValidation;

import javax.security.auth.login.LoginException;
import java.util.HashMap;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class CloneVirtualMachine extends ProxmoxBuildStep {

    private final String targetVmId;
    private final String cloneName;
    private final boolean startAfterClone;
    private final String snapshotName;
    
    @DataBoundConstructor
    public CloneVirtualMachine(String datacenterDescription, String datacenterNode, String vmId,
                              String targetVmId, String cloneName, boolean startAfterClone, 
                              String snapshotName) {
        super(datacenterDescription, datacenterNode, vmId);
        this.targetVmId = targetVmId;
        this.cloneName = cloneName;
        this.startAfterClone = startAfterClone;
        this.snapshotName = snapshotName;
    }
    
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) 
            throws InterruptedException, AbortException {
        
        logInfo(listener, "Cloning VM " + vmId + " to new VM " + targetVmId + " (" + cloneName + ")");
        
        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer sourceVmId = parseVmId();
            Integer targetVmIdInt = parseTargetVmId();
            
            // Check if target VM ID already exists
            HashMap<String, Integer> existingVms = proxmoxApi.getQemuMachines(datacenterNode);
            if (existingVms.containsValue(targetVmIdInt)) {
                throw new AbortException("Target VM ID " + targetVmId + " already exists");
            }
            
            // Clone the VM
            String taskId = proxmoxApi.cloneQemuMachine(datacenterNode, sourceVmId, targetVmIdInt, cloneName);
            logInfo(listener, "Started cloning VM, task ID: " + taskId);
            
            // Wait for cloning to complete
            logInfo(listener, "Waiting for cloning to complete...");
            try {
                proxmoxApi.waitForTaskToFinish(datacenterNode, taskId);
                logInfo(listener, "Cloning completed successfully");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AbortException("Cloning was interrupted");
            }
            
            // Revert to snapshot if specified
            if (snapshotName != null && !snapshotName.isEmpty()) {
                logInfo(listener, "Reverting cloned VM to snapshot: " + snapshotName);
                String rollbackTaskId = proxmoxApi.rollbackQemuMachineSnapshot(datacenterNode, targetVmIdInt, snapshotName);
                logInfo(listener, "Snapshot rollback task ID: " + rollbackTaskId);
            }
            
            // Start VM if requested
            if (startAfterClone) {
                logInfo(listener, "Starting cloned VM");
                String startTaskId = proxmoxApi.startQemuMachine(datacenterNode, targetVmIdInt);
                logInfo(listener, "VM start task ID: " + startTaskId);
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
    
    public boolean getStartAfterClone() {
        return startAfterClone;
    }
    
    public String getSnapshotName() {
        return snapshotName;
    }
    
    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {
        
        @Override
        public String getDisplayName() {
            return "Proxmox: Clone Virtual Machine";
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
                return FormValidation.error("Invalid clone name format");
            }
            return FormValidation.ok();
        }
    }
}