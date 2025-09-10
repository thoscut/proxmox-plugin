package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import javax.security.auth.login.LoginException;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;

public class StopVirtualMachine extends ProxmoxBuildStep {

    private final boolean gracefulShutdown;
    private final int shutdownWaitSeconds;
    
    @DataBoundConstructor
    public StopVirtualMachine(String datacenterDescription, String datacenterNode, String vmId, 
                             boolean gracefulShutdown, int shutdownWaitSeconds) {
        super(datacenterDescription, datacenterNode, vmId);
        this.gracefulShutdown = gracefulShutdown;
        this.shutdownWaitSeconds = shutdownWaitSeconds;
    }
    
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) 
            throws InterruptedException, AbortException {
        
        logInfo(listener, "Stopping VM " + vmId + " on node " + datacenterNode + 
               (gracefulShutdown ? " (graceful shutdown)" : " (forced stop)"));
        
        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();
            
            // Check if VM is already stopped
            if (!proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "VM " + vmId + " is already stopped");
                return true;
            }
            
            // Stop or shutdown the VM
            String taskId;
            if (gracefulShutdown) {
                taskId = proxmoxApi.shutdownQemuMachine(datacenterNode, vmIdInt);
                logInfo(listener, "Initiated graceful shutdown of VM " + vmId + ", task ID: " + taskId);
            } else {
                taskId = proxmoxApi.stopQemuMachine(datacenterNode, vmIdInt);
                logInfo(listener, "Initiated forced stop of VM " + vmId + ", task ID: " + taskId);
            }
            
            // Wait for shutdown if specified
            if (shutdownWaitSeconds > 0) {
                logInfo(listener, "Waiting " + shutdownWaitSeconds + " seconds for VM shutdown");
                Thread.sleep(shutdownWaitSeconds * 1000L);
            }
            
            // Verify VM is stopped
            if (!proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "VM " + vmId + " stopped successfully");
                return true;
            } else {
                logError(listener, "VM " + vmId + " is still running after stop command", null);
                return false;
            }
            
        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to stop VM", e);
            throw new AbortException("Failed to stop VM: " + e.getMessage());
        }
    }
    
    public boolean getGracefulShutdown() {
        return gracefulShutdown;
    }
    
    public int getShutdownWaitSeconds() {
        return shutdownWaitSeconds;
    }
    
    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {
        
        @Override
        public String getDisplayName() {
            return "Proxmox: Stop Virtual Machine";
        }
    }
}