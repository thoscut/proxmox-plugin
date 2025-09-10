package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import javax.security.auth.login.LoginException;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;

public class StartVirtualMachine extends ProxmoxBuildStep {

    private final int startupWaitSeconds;
    
    @DataBoundConstructor
    public StartVirtualMachine(String datacenterDescription, String datacenterNode, String vmId, 
                              int startupWaitSeconds) {
        super(datacenterDescription, datacenterNode, vmId);
        this.startupWaitSeconds = startupWaitSeconds;
    }
    
    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) 
            throws InterruptedException, AbortException {
        
        logInfo(listener, "Starting VM " + vmId + " on node " + datacenterNode);
        
        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();
            
            // Check if VM is already running
            if (proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "VM " + vmId + " is already running");
                return true;
            }
            
            // Start the VM
            String taskId = proxmoxApi.startQemuMachine(datacenterNode, vmIdInt);
            logInfo(listener, "Started VM " + vmId + ", task ID: " + taskId);
            
            // Wait for startup if specified
            if (startupWaitSeconds > 0) {
                logInfo(listener, "Waiting " + startupWaitSeconds + " seconds for VM startup");
                Thread.sleep(startupWaitSeconds * 1000L);
            }
            
            // Verify VM is running
            if (proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "VM " + vmId + " started successfully");
                return true;
            } else {
                logError(listener, "VM " + vmId + " failed to start", null);
                return false;
            }
            
        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to start VM", e);
            throw new AbortException("Failed to start VM: " + e.getMessage());
        }
    }
    
    public int getStartupWaitSeconds() {
        return startupWaitSeconds;
    }
    
    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {
        
        @Override
        public String getDisplayName() {
            return "Proxmox: Start Virtual Machine";
        }
    }
}