package org.jenkinsci.plugins.proxmox;

import hudson.AbortException;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.DelegatingComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.io.ObjectStreamException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Controls launching of Proxmox virtual machines.
 */
public class VirtualMachineLauncher extends DelegatingComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(VirtualMachineLauncher.class.getName());

    @Deprecated
    private transient ComputerLauncher delegate;

    @Deprecated
    private transient int WAIT_TIME_MS;

    private String datacenterDescription;
    private String datacenterNode;
    private Integer virtualMachineId;
    private String snapshotName;
    private Boolean startVM;
    private int waitingTimeSecs;

    public static enum RevertPolicy {
        AFTER_CONNECT("After connect to the virtual machine"),
        BEFORE_JOB("Before every job executing on the virtual machine");

        private final String label;

        private RevertPolicy(String policy) {
            this.label = policy;
        }

        public String getLabel() {
            return label;
        }
    }

    private final RevertPolicy revertPolicy;

    @DataBoundConstructor
    public VirtualMachineLauncher(
            ComputerLauncher launcher,
            String datacenterDescription,
            String datacenterNode,
            Integer virtualMachineId,
            String snapshotName,
            Boolean startVM,
            int waitingTimeSecs,
            RevertPolicy revertPolicy) {
        super(launcher);
        this.datacenterDescription = datacenterDescription;
        this.datacenterNode = datacenterNode;
        this.virtualMachineId = virtualMachineId;
        this.snapshotName = snapshotName;
        this.startVM = startVM;
        this.waitingTimeSecs = waitingTimeSecs;
        this.revertPolicy = revertPolicy;
    }

    /**
     * Migrates instances from the old parent class to the new parent class.
     * @return the deserialized instance.
     * @throws ObjectStreamException if something went wrong.
     */
    private Object readResolve() throws ObjectStreamException {
        if (delegate != null) {
            return new VirtualMachineLauncher(
                    delegate,
                    datacenterDescription,
                    datacenterNode,
                    virtualMachineId,
                    snapshotName,
                    startVM,
                    WAIT_TIME_MS / 1000,
                    revertPolicy);
        }
        return this;
    }

    /**
     * @return actual launcher
     * @deprecated use {@link #getLauncher()}
     */
    @Deprecated
    public ComputerLauncher getDelegate() {
        return launcher;
    }

    public String getDatacenterDescription() {
        return datacenterDescription;
    }

    public String getDatacenterNode() {
        return datacenterNode;
    }

    public Integer getVirtualMachineId() {
        return virtualMachineId;
    }

    public String getSnapshotName() {
        return snapshotName;
    }

    public Boolean getStartVM() {
        return startVM;
    }

    public int getWaitingTimeSecs() {
        return waitingTimeSecs;
    }

    public RevertPolicy getRevertPolicy() {
        return revertPolicy;
    }

    public Datacenter findDatacenterInstance() throws RuntimeException {
        if (datacenterDescription != null && virtualMachineId != null) {
            for (Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof Datacenter
                        && ((Datacenter) cloud).getDatacenterDescription().equals(datacenterDescription)) {
                    return (Datacenter) cloud;
                }
            }
        }
        throw new RuntimeException("Could not find the proxmox datacenter instance!");
    }

    @Override
    public boolean isLaunchSupported() {
        // TODO: Add this into the settings for node setup
        boolean overrideLaunchSupported = launcher.isLaunchSupported();
        // Support launching for the JNLPLauncher, so the `launch` function gets called
        // and the VM can be reset to a snapshot.
        if (launcher instanceof JNLPLauncher) {
            overrideLaunchSupported = true;
        }
        return overrideLaunchSupported;
    }

    private Connector proxmoxConnector() throws AbortException {
        try {
            return findDatacenterInstance().proxmoxInstance();
        } catch (RuntimeException e) {
            throw new AbortException(e.getMessage());
        }
    }

    private boolean isSnapshotRevertConfigured() {
        return snapshotName != null && !snapshotName.isEmpty() && !"current".equals(snapshotName);
    }

    private void waitForStartup() throws InterruptedException {
        // Ignore the wait period for a JNLP agent as it connects back to the Jenkins instance.
        if (!(launcher instanceof JNLPLauncher)) {
            Thread.sleep((long) waitingTimeSecs * 1000);
        }
    }

    /**
     * Starts the virtual machine unless it is already running.
     *
     * @return whether the virtual machine was started.
     * @throws IOException if the virtual machine could not be started.
     */
    public boolean startSlaveIfNeeded(TaskListener taskListener) throws IOException, InterruptedException {
        Connector pve = proxmoxConnector();
        try {
            if (pve.isQemuMachineRunning(datacenterNode, virtualMachineId)) {
                return false;
            }
            taskListener.getLogger().println("Starting virtual machine...");
            String taskId = pve.startQemuMachine(datacenterNode, virtualMachineId);
            JSONObject taskStatus = pve.waitForTaskToFinish(datacenterNode, taskId);
            taskListener.getLogger().println("Task finished! Status object: " + taskStatus);
            if (!Connector.isTaskSuccessful(taskStatus)) {
                throw new AbortException("Starting virtual machine \"" + virtualMachineId + "\" failed: " + taskStatus);
            }
            return true;
        } catch (LoginException e) {
            throw new AbortException("Login to Proxmox failed: " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }
    }

    /**
     * Reverts the virtual machine to the configured snapshot, starts it if configured and waits for the
     * configured startup period.
     *
     * @throws IOException if reverting or starting the virtual machine failed.
     */
    public void revertSnapshot(SlaveComputer slaveComputer, TaskListener taskListener)
            throws IOException, InterruptedException {
        boolean revert = isSnapshotRevertConfigured();
        if (revert) {
            Connector pve = proxmoxConnector();
            taskListener
                    .getLogger()
                    .println("Virtual machine \"" + virtualMachineId + "\" (Name \""
                            + slaveComputer.getDisplayName() + "\") is being reverted...");
            try {
                String taskId = pve.rollbackQemuMachineSnapshot(datacenterNode, virtualMachineId, snapshotName);
                taskListener.getLogger().println("Proxmox returned: " + taskId);

                // Wait for the task to finish
                JSONObject taskStatus = pve.waitForTaskToFinish(datacenterNode, taskId);
                taskListener.getLogger().println("Task finished! Status object: " + taskStatus);
                if (!Connector.isTaskSuccessful(taskStatus)) {
                    throw new AbortException("Reverting virtual machine \"" + virtualMachineId + "\" to snapshot \""
                            + snapshotName + "\" failed: " + taskStatus);
                }
            } catch (LoginException e) {
                throw new AbortException("Login to Proxmox failed: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                throw new AbortException(e.getMessage());
            }
        }

        boolean started = Boolean.TRUE.equals(startVM) && startSlaveIfNeeded(taskListener);

        if (revert || started) {
            waitForStartup();
        }
    }

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener taskListener)
            throws IOException, InterruptedException {
        if (revertPolicy == RevertPolicy.AFTER_CONNECT) {
            revertSnapshot(slaveComputer, taskListener);
        } else if (Boolean.TRUE.equals(startVM) && startSlaveIfNeeded(taskListener)) {
            waitForStartup();
        }

        launcher.launch(slaveComputer, taskListener);
    }

    public void shutdown(SlaveComputer slaveComputer, TaskListener taskListener) {
        // try to gracefully shutdown the virtual machine
        try {
            taskListener
                    .getLogger()
                    .println("Virtual machine \"" + virtualMachineId + "\" (slave \"" + slaveComputer.getDisplayName()
                            + "\") is being shutdown.");
            Connector pve = proxmoxConnector();
            String taskId = pve.shutdownQemuMachine(datacenterNode, virtualMachineId);
            JSONObject taskStatus = pve.waitForTaskToFinish(datacenterNode, taskId);
            if (!Connector.isTaskSuccessful(taskStatus)) {
                // Graceful shutdown failed, so doing a stop.
                taskListener
                        .getLogger()
                        .println("Virtual machine \"" + virtualMachineId + "\" (slave \""
                                + slaveComputer.getDisplayName()
                                + "\") was not able to shutdown, doing a stop instead");
                taskId = pve.stopQemuMachine(datacenterNode, virtualMachineId);
                taskStatus = pve.waitForTaskToFinish(datacenterNode, taskId);
            }
            taskListener.getLogger().println("Task finished! Status object: " + taskStatus);
        } catch (InterruptedException e) {
            LOGGER.log(Level.WARNING, "Waiting for task completion was interrupted", e);
            Thread.currentThread().interrupt();
        } catch (LoginException | IOException | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Shutting down virtual machine " + virtualMachineId + " failed", e);
            taskListener.getLogger().println("ERROR: Shutting down virtual machine failed: " + e.getMessage());
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer slaveComputer, TaskListener taskListener) {
        try {
            if (revertPolicy == RevertPolicy.AFTER_CONNECT) {
                shutdown(slaveComputer, taskListener);
            }
        } finally {
            super.beforeDisconnect(slaveComputer, taskListener);
        }
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
