package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;

import javax.security.auth.login.LoginException;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;

public class ConvertToTemplate extends ProxmoxBuildStep {

    private final boolean stopVmFirst;

    @DataBoundConstructor
    public ConvertToTemplate(String datacenterDescription, String datacenterNode, String vmId,
                            boolean stopVmFirst) {
        super(datacenterDescription, datacenterNode, vmId);
        this.stopVmFirst = stopVmFirst;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
            throws InterruptedException, AbortException {

        logInfo(listener, "Converting VM " + vmId + " to template");

        try {
            Connector proxmoxApi = getProxmoxConnector();
            Integer vmIdInt = parseVmId();

            // Check if VM exists
            if (!proxmoxApi.getQemuMachines(datacenterNode).containsValue(vmIdInt)) {
                throw new AbortException("VM " + vmId + " not found on node " + datacenterNode);
            }

            // Stop VM if requested and running
            boolean wasRunning = proxmoxApi.isQemuMachineRunning(datacenterNode, vmIdInt);
            if (wasRunning) {
                if (stopVmFirst) {
                    logInfo(listener, "Stopping VM before converting to template");
                    String stopTaskId = proxmoxApi.stopQemuMachine(datacenterNode, vmIdInt);
                    logInfo(listener, "Stop task ID: " + stopTaskId);

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
                } else {
                    throw new AbortException("VM " + vmId + " is currently running. Either stop it first or enable 'Stop VM first' option");
                }
            }

            // Convert to template
            String taskId = proxmoxApi.convertQemuMachineToTemplate(datacenterNode, vmIdInt);
            logInfo(listener, "Started template conversion, task ID: " + taskId);

            // Wait for the task to complete
            try {
                proxmoxApi.waitForTaskToFinish(datacenterNode, taskId);
                logInfo(listener, "Template conversion completed");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AbortException("Template conversion was interrupted");
            }

            // Verify conversion (template VMs show up differently in API responses)
            logInfo(listener, "Successfully converted VM " + vmId + " to template");

            if (wasRunning && stopVmFirst) {
                logInfo(listener, "Note: Template cannot be started like a regular VM. Clone it first to create new VMs.");
            }

            return true;

        } catch (LoginException e) {
            logError(listener, "Authentication failed", e);
            throw new AbortException("Authentication failed: " + e.getMessage());
        } catch (Exception e) {
            logError(listener, "Failed to convert VM to template", e);
            throw new AbortException("Failed to convert VM to template: " + e.getMessage());
        }
    }

    public boolean getStopVmFirst() {
        return stopVmFirst;
    }

    @Extension
    public static final class DescriptorImpl extends ProxmoxBuildStepDescriptor {

        @Override
        public String getDisplayName() {
            return "Proxmox: Convert VM to Template";
        }
    }
}