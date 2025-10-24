package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Run;
import hudson.model.TaskListener;

import javax.security.auth.login.LoginException;
import java.io.IOException;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import edu.umd.cs.findbugs.annotations.NonNull;

public class PauseVirtualMachine extends ProxmoxBuildStep {

    private final boolean waitForCompletion;

    @DataBoundConstructor
    public PauseVirtualMachine(String datacenterDescription, String datacenterNode, String vmId,
                              boolean waitForCompletion) {
        super(datacenterDescription, datacenterNode, vmId);
        this.waitForCompletion = waitForCompletion;
    }

    @Override
    public void perform(@NonNull Run<?, ?> run, @NonNull FilePath workspace, @NonNull Launcher launcher,
                       @NonNull TaskListener listener) throws InterruptedException, IOException {

        logInfo(listener, "Pausing VM " + vmId + " on node " + datacenterNode);

        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();

            // Check if VM is currently running
            if (!proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "VM " + vmId + " is not running, cannot pause");
                throw new AbortException("VM " + vmId + " is not running, cannot pause");
            }

            // Pause the VM
            String taskId = proxmoxApi.pauseQemuMachine(datacenterNode, vmIdInt);
            logInfo(listener, "Started VM pause operation, task ID: " + taskId);

            // Wait for completion if requested
            if (waitForCompletion) {
                logInfo(listener, "Waiting for pause operation to complete...");
                try {
                    proxmoxApi.waitForTaskToFinish(datacenterNode, taskId);
                    logInfo(listener, "VM pause operation completed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AbortException("VM pause operation was interrupted");
                }

                // Verify VM is paused - check status
                try {
                    Thread.sleep(2000); // Give Proxmox time to update status
                    boolean isRunning = proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt);
                    if (isRunning) {
                        logError(listener, "VM appears to still be running after pause operation", null);
                        throw new AbortException("VM appears to still be running after pause operation");
                    } else {
                        logInfo(listener, "VM " + vmId + " has been successfully paused");
                    }
                } catch (AbortException e) {
                    throw e;
                } catch (Exception e) {
                    logInfo(listener, "Could not verify VM status after pause, but operation completed");
                }
            }

            logInfo(listener, "VM pause operation initiated successfully");

        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (AbortException e) {
            throw e;
        } catch (Exception e) {
            logError(listener, "Failed to pause VM", e);
            throw new AbortException("Failed to pause VM: " + e.getMessage());
        }
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, AbortException {

        logInfo(listener, "Pausing VM " + vmId + " on node " + datacenterNode);

        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();

            // Check if VM is currently running
            if (!proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt)) {
                logInfo(listener, "VM " + vmId + " is not running, cannot pause");
                return false;
            }

            // Pause the VM
            String taskId = proxmoxApi.pauseQemuMachine(datacenterNode, vmIdInt);
            logInfo(listener, "Started VM pause operation, task ID: " + taskId);

            // Wait for completion if requested
            if (waitForCompletion) {
                logInfo(listener, "Waiting for pause operation to complete...");
                try {
                    proxmoxApi.waitForTaskToFinish(datacenterNode, taskId);
                    logInfo(listener, "VM pause operation completed");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AbortException("VM pause operation was interrupted");
                }

                // Verify VM is paused - check status
                try {
                    Thread.sleep(2000); // Give Proxmox time to update status
                    boolean isRunning = proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt);
                    if (isRunning) {
                        logError(listener, "VM appears to still be running after pause operation", null);
                        return false;
                    } else {
                        logInfo(listener, "VM " + vmId + " has been successfully paused");
                    }
                } catch (Exception e) {
                    logInfo(listener, "Could not verify VM status after pause, but operation completed");
                }
            }

            logInfo(listener, "VM pause operation initiated successfully");
            return true;

        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to pause VM", e);
            throw new AbortException("Failed to pause VM: " + e.getMessage());
        }
    }

    public boolean getWaitForCompletion() {
        return waitForCompletion;
    }

    @Extension
    @Symbol("proxmoxPauseVm")
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "Proxmox: Pause Virtual Machine";
        }
    }
}