package org.jenkinsci.plugins.proxmox;

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
        this.waitingTimeSecs = Math.max(0, waitingTimeSecs);
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
                        && datacenterDescription.equals(((Datacenter) cloud).getDatacenterDescription())) {
                    return (Datacenter) cloud;
                }
            }
        }
        throw new IllegalStateException("Could not find the proxmox datacenter instance '" + datacenterDescription
                + "' for virtual machine " + virtualMachineId + "!");
    }

    private Connector connector() throws IOException {
        try {
            return findDatacenterInstance().proxmoxInstance();
        } catch (IllegalStateException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Legacy configurations, which were created before the revert policy existed, always reverted after connect.
     */
    private RevertPolicy effectiveRevertPolicy() {
        return revertPolicy == null ? RevertPolicy.AFTER_CONNECT : revertPolicy;
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

    private static String describe(JSONObject taskStatus) {
        if (taskStatus == null) {
            return "no status";
        }
        return "status=" + taskStatus.optString("status") + ", exitstatus=" + taskStatus.optString("exitstatus");
    }

    /**
     * Waits for a Proxmox task and fails if it did not finish successfully.
     */
    private void awaitTask(Connector pve, String taskId, String action, TaskListener taskListener)
            throws IOException, InterruptedException, LoginException {
        JSONObject taskStatus = pve.waitForTaskToFinish(datacenterNode, taskId);
        taskListener.getLogger().println("Task finished! Status object: " + taskStatus);
        if (!Connector.isTaskSuccessful(taskStatus)) {
            throw new IOException(
                    "Proxmox task to " + action + " virtual machine " + virtualMachineId + " failed ("
                            + describe(taskStatus) + ")");
        }
    }

    public void startSlaveIfNeeded(TaskListener taskListener) throws IOException, InterruptedException {
        try {
            Connector pve = connector();
            if (!pve.isQemuMachineRunning(datacenterNode, virtualMachineId)) {
                taskListener.getLogger().println("Starting virtual machine...");
                String taskId = pve.startQemuMachine(datacenterNode, virtualMachineId);
                awaitTask(pve, taskId, "start", taskListener);
            }
        } catch (LoginException e) {
            throw new IOException("Login to Proxmox failed: " + e.getMessage(), e);
        }
    }

    public void revertSnapshot(SlaveComputer slaveComputer, TaskListener taskListener)
            throws IOException, InterruptedException {
        try {
            Connector pve = connector();

            if (snapshotName != null && !snapshotName.isEmpty() && !"current".equals(snapshotName)) {
                taskListener
                        .getLogger()
                        .println("Virtual machine \"" + virtualMachineId + "\" (Name \""
                                + slaveComputer.getDisplayName() + "\") is being reverted...");
                String taskId = pve.rollbackQemuMachineSnapshot(datacenterNode, virtualMachineId, snapshotName);
                taskListener.getLogger().println("Proxmox returned: " + taskId);
                awaitTask(pve, taskId, "roll back", taskListener);
            }
        } catch (LoginException e) {
            throw new IOException("Login to Proxmox failed: " + e.getMessage(), e);
        }

        if (Boolean.TRUE.equals(startVM)) {
            startSlaveIfNeeded(taskListener);
        }

        // Ignore the wait period for a JNLP agent as it connects back to the Jenkins instance.
        if (!(launcher instanceof JNLPLauncher) && waitingTimeSecs > 0) {
            Thread.sleep(waitingTimeSecs * 1000L);
        }
    }

    @Override
    public void launch(SlaveComputer slaveComputer, TaskListener taskListener)
            throws IOException, InterruptedException {
        if (effectiveRevertPolicy() == RevertPolicy.AFTER_CONNECT) {
            revertSnapshot(slaveComputer, taskListener);
        } else {
            if (Boolean.TRUE.equals(startVM)) {
                startSlaveIfNeeded(taskListener);
            }
        }

        launcher.launch(slaveComputer, taskListener);
    }

    public void shutdown(SlaveComputer slaveComputer, TaskListener taskListener) {
        taskListener
                .getLogger()
                .println("Virtual machine \"" + virtualMachineId + "\" (slave \"" + slaveComputer.getDisplayName()
                        + "\") is being shutdown.");
        Connector pve;
        try {
            pve = connector();
        } catch (IOException e) {
            taskListener.getLogger().println("ERROR: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Could not shut down virtual machine " + virtualMachineId, e);
            return;
        }

        // try to gracefully shutdown the virtual machine
        try {
            String taskId = pve.shutdownQemuMachine(datacenterNode, virtualMachineId);
            JSONObject taskStatus = pve.waitForTaskToFinish(datacenterNode, taskId);
            if (Connector.isTaskSuccessful(taskStatus)) {
                taskListener.getLogger().println("Task finished! Status object: " + taskStatus);
                return;
            }
            taskListener.getLogger().println("Graceful shutdown failed (" + describe(taskStatus) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Interrupted while shutting down virtual machine " + virtualMachineId, e);
            return;
        } catch (LoginException | IOException e) {
            taskListener.getLogger().println("Graceful shutdown failed: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Graceful shutdown of virtual machine " + virtualMachineId + " failed", e);
        }

        // Graceful shutdown failed, so doing a stop.
        taskListener
                .getLogger()
                .println("Virtual machine \"" + virtualMachineId + "\" (slave \"" + slaveComputer.getDisplayName()
                        + "\") was not able to shutdown, doing a stop instead");
        try {
            String taskId = pve.stopQemuMachine(datacenterNode, virtualMachineId);
            JSONObject taskStatus = pve.waitForTaskToFinish(datacenterNode, taskId);
            taskListener.getLogger().println("Task finished! Status object: " + taskStatus);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Interrupted while stopping virtual machine " + virtualMachineId, e);
        } catch (LoginException | IOException e) {
            taskListener.getLogger().println("ERROR: Stopping the virtual machine failed: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Stopping virtual machine " + virtualMachineId + " failed", e);
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer slaveComputer, TaskListener taskListener) {
        if (effectiveRevertPolicy() == RevertPolicy.AFTER_CONNECT) shutdown(slaveComputer, taskListener);

        super.beforeDisconnect(slaveComputer, taskListener);
    }

    @Override
    public Descriptor<ComputerLauncher> getDescriptor() {
        throw new UnsupportedOperationException();
    }
}
