package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import javax.security.auth.login.LoginException;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;

public class ResumeVirtualMachine extends ProxmoxBuildStep {

    private final int waitSeconds;

    @DataBoundConstructor
    public ResumeVirtualMachine(String datacenterDescription, String datacenterNode, String vmId,
                               int waitSeconds) {
        super(datacenterDescription, datacenterNode, vmId);
        this.waitSeconds = waitSeconds;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, AbortException {

        logInfo(listener, "Resuming VM " + vmId + " on node " + datacenterNode);

        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();

            // Check if VM is currently running
            if (proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "VM " + vmId + " is already running");
                return true;
            }

            // Resume the VM
            String taskId = proxmoxApi.resumeQemuMachine(datacenterNode, vmIdInt);
            logInfo(listener, "Started VM resume operation, task ID: " + taskId);

            // Wait for specified time if configured
            if (waitSeconds > 0) {
                logInfo(listener, "Waiting " + waitSeconds + " seconds for VM to resume fully");
                Thread.sleep(waitSeconds * 1000L);

                // Verify VM is running
                if (proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                    logInfo(listener, "VM " + vmId + " resumed successfully and is now running");
                } else {
                    logError(listener, "VM " + vmId + " failed to resume properly", null);
                    return false;
                }
            } else {
                logInfo(listener, "VM resume operation initiated (not waiting for completion)");
            }

            return true;

        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to resume VM", e);
            throw new AbortException("Failed to resume VM: " + e.getMessage());
        }
    }

    public int getWaitSeconds() {
        return waitSeconds;
    }

    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "Proxmox: Resume Virtual Machine";
        }
    }
}