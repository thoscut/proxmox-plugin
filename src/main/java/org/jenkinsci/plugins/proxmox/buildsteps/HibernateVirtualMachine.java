package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import javax.security.auth.login.LoginException;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;

public class HibernateVirtualMachine extends ProxmoxBuildStep {

    private final boolean waitForCompletion;

    @DataBoundConstructor
    public HibernateVirtualMachine(String datacenterDescription, String datacenterNode, String vmId,
                                  boolean waitForCompletion) {
        super(datacenterDescription, datacenterNode, vmId);
        this.waitForCompletion = waitForCompletion;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, AbortException {

        logInfo(listener, "Hibernating VM " + vmId + " on node " + datacenterNode + " (suspend to disk)");

        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();

            // Check if VM is currently running
            if (!proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "VM " + vmId + " is not running, cannot hibernate");
                return false;
            }

            // Hibernate the VM (suspend to disk)
            String taskId = proxmoxApi.hibernateQemuMachine(datacenterNode, vmIdInt);
            logInfo(listener, "Started VM hibernation operation (suspend to disk), task ID: " + taskId);

            // Wait for completion if requested
            if (waitForCompletion) {
                logInfo(listener, "Waiting for hibernation operation to complete...");
                try {
                    proxmoxApi.waitForTaskToFinish(datacenterNode, taskId);
                    logInfo(listener, "VM hibernation operation completed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AbortException("VM hibernation operation was interrupted");
                }

                // Verify VM is hibernated - check status
                try {
                    Thread.sleep(3000); // Give Proxmox more time for hibernation (disk write operation)
                    boolean isRunning = proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt);
                    if (isRunning) {
                        logError(listener, "VM appears to still be running after hibernation operation", null);
                        return false;
                    } else {
                        logInfo(listener, "VM " + vmId + " has been successfully hibernated (suspended to disk)");
                    }
                } catch (Exception e) {
                    logInfo(listener, "Could not verify VM status after hibernation, but operation completed");
                }
            } else {
                logInfo(listener, "Note: Hibernation saves VM state to disk and may take longer than pause");
            }

            logInfo(listener, "VM hibernation operation initiated successfully");
            return true;

        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to hibernate VM", e);
            throw new AbortException("Failed to hibernate VM: " + e.getMessage());
        }
    }

    public boolean getWaitForCompletion() {
        return waitForCompletion;
    }

    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "Hibernate Virtual Machine";
        }
    }
}