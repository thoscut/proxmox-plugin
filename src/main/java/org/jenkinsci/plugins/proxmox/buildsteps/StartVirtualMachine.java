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
            
            // Wait for startup if specified - check VM is running and agent is responding
            if (startupWaitSeconds > 0) {
                logInfo(listener, "Waiting up to " + startupWaitSeconds + " seconds for VM startup and guest agent");
                int checks = startupWaitSeconds / 2; // Check every 2 seconds
                boolean vmReady = false;

                for (int i = 0; i < checks; i++) {
                    Thread.sleep(2000);

                    // Check if VM is running
                    if (proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                        // VM is running, now check if guest agent responds
                        try {
                            if (proxmoxApi.isGuestAgentAvailable(datacenterNode, vmIdInt)) {
                                int elapsed = (i + 1) * 2;
                                logInfo(listener, "VM and guest agent ready after " + elapsed + " seconds");
                                vmReady = true;
                                break;
                            }
                        } catch (Exception e) {
                            // Guest agent not ready yet, continue waiting
                        }
                    }
                }

                if (!vmReady) {
                    logInfo(listener, "Reached timeout after " + startupWaitSeconds + " seconds");
                }
            }

            // Verify VM is running
            if (proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "VM " + vmId + " started successfully");

                // Note: Guest agent ping may succeed before the agent is ready to execute commands.
                // RunCommand has robust retry logic to handle this properly.

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
            return "Start Virtual Machine";
        }
    }
}