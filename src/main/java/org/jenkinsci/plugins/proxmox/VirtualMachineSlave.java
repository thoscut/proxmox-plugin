package org.jenkinsci.plugins.proxmox;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * An agent running in a Proxmox virtual machine. It is either configured by hand for an existing
 * virtual machine, or provisioned on demand by a {@link ProxmoxCloudSlaveTemplate}, which clones a
 * template for it; only the latter owns its virtual machine and deletes it when the agent is removed.
 */
public class VirtualMachineSlave extends AbstractCloudSlave implements TrackedItem {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(VirtualMachineSlave.class.getName());

    private String datacenterDescription;
    private String datacenterNode;
    private String snapshotName;
    private Integer virtualMachineId;
    private Boolean startVM;
    private int startupWaitingPeriodSeconds;
    private RevertPolicy revertPolicy;
    private transient int limitedBuildsCount = 0;
    private transient int buildsExecuted = 0;

    /** Set for agents provisioned by the cloud; identifies them for the cloud statistics plugin. */
    private ProvisioningActivity.Id provisioningId;

    @DataBoundConstructor
    public VirtualMachineSlave(
            String name,
            String nodeDescription,
            String remoteFS,
            String numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher delegateLauncher,
            RetentionStrategy<?> retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties,
            String datacenterDescription,
            String datacenterNode,
            Integer virtualMachineId,
            String snapshotName,
            Boolean startVM,
            int startupWaitingPeriodSeconds,
            RevertPolicy revertPolicy)
            throws IOException, Descriptor.FormException {
        super(
                name,
                remoteFS,
                new VirtualMachineLauncher(
                        delegateLauncher,
                        datacenterDescription,
                        datacenterNode,
                        virtualMachineId,
                        snapshotName,
                        startVM,
                        startupWaitingPeriodSeconds,
                        revertPolicy));
        setNodeDescription(nodeDescription);
        setNumExecutors(Util.tryParseNumber(numExecutors, 1).intValue());
        setMode(mode);
        setLabelString(labelString);
        if (retentionStrategy != null) {
            setRetentionStrategy(retentionStrategy);
        }
        if (nodeProperties != null) {
            getNodeProperties().replaceBy(nodeProperties);
        }
        this.datacenterDescription = datacenterDescription;
        this.datacenterNode = datacenterNode;
        this.virtualMachineId = virtualMachineId;
        this.snapshotName = snapshotName;
        this.startVM = startVM;
        this.startupWaitingPeriodSeconds = startupWaitingPeriodSeconds;
        this.revertPolicy = revertPolicy;
        checkExecutorsForRevertPolicy(getNumExecutors(), revertPolicy);
    }

    /**
     * Reverting before every job disconnects the agent and rolls the whole VM back, which would
     * kill any build running on another executor of the same agent.
     */
    static void checkExecutorsForRevertPolicy(int numExecutors, RevertPolicy revertPolicy)
            throws Descriptor.FormException {
        if (revertPolicy == RevertPolicy.BEFORE_JOB && numExecutors > 1) {
            throw new Descriptor.FormException(
                    "The revert policy 'Before every job' requires exactly one executor", "numExecutors");
        }
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

    public int getStartupWaitingPeriodSeconds() {
        return startupWaitingPeriodSeconds;
    }

    public RevertPolicy getRevertPolicy() {
        return revertPolicy;
    }

    public ComputerLauncher getDelegateLauncher() {
        ComputerLauncher launcher = getLauncher();
        if (launcher instanceof VirtualMachineLauncher) {
            return ((VirtualMachineLauncher) launcher).getLauncher();
        }
        return launcher;
    }

    /**
     * Configuration as Code sets the {@code launcher} attribute after construction. Wrap it,
     * so the Proxmox handling (revert, start and shutdown of the virtual machine) is kept.
     */
    @Override
    @DataBoundSetter
    public void setLauncher(ComputerLauncher launcher) {
        if (!(launcher instanceof VirtualMachineLauncher)) {
            launcher = new VirtualMachineLauncher(
                    launcher,
                    datacenterDescription,
                    datacenterNode,
                    virtualMachineId,
                    snapshotName,
                    startVM,
                    startupWaitingPeriodSeconds,
                    revertPolicy);
        }
        super.setLauncher(launcher);
    }

    public void setLimitedBuildsCount(int count) {
        this.limitedBuildsCount = count;
    }

    public int getLimitedBuildsCount() {
        return limitedBuildsCount;
    }

    public int getBuildsExecuted() {
        return buildsExecuted;
    }

    /** {@link TrackedItem} implementation for the cloud statistics plugin. */
    @Override
    @CheckForNull
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    public void setProvisioningId(ProvisioningActivity.Id id) {
        this.provisioningId = id;
    }

    /**
     * @return whether the cloud cloned the virtual machine for this agent. Only then the virtual
     *     machine belongs to the agent and may be deleted together with it; a virtual machine that
     *     was assigned to an agent by hand is never deleted.
     */
    public boolean isProvisionedByCloud() {
        return provisioningId != null;
    }

    /**
     * Counts a finished build and removes the agent, together with its cloned virtual machine,
     * once the configured number of builds was reached.
     */
    public void incrementBuildsExecuted() {
        buildsExecuted++;
        if (limitedBuildsCount <= 0 || buildsExecuted < limitedBuildsCount) {
            return;
        }
        try {
            if (isProvisionedByCloud()) {
                deleteVirtualMachine();
            }

            // Close the channel before removing the node to prevent WebSocket timeout errors
            Computer computer = toComputer();
            VirtualChannel channel = computer == null ? null : computer.getChannel();
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to close the channel of " + getNodeName() + " before removal", e);
                }
            }

            Jenkins.get().removeNode(this);
            LOGGER.log(Level.INFO, "Agent {0} deprovisioned after {1} builds", new Object[] {
                getNodeName(), limitedBuildsCount
            });
        } catch (IOException | RuntimeException e) {
            // Log, but do not fail the build
            LOGGER.log(Level.WARNING, "Failed to deprovision agent " + getNodeName() + " after limited builds", e);
        }
    }

    /** Stops and deletes the cloned virtual machine of this agent; failures are logged. */
    private void deleteVirtualMachine() {
        Datacenter datacenter = Datacenter.findByDescription(datacenterDescription);
        if (datacenter == null || virtualMachineId == null || datacenterNode == null) {
            return;
        }
        try {
            Connector pveApi = datacenter.proxmoxInstance();
            if (pveApi.isQemuMachineRunning(datacenterNode, virtualMachineId)) {
                LOGGER.log(Level.INFO, "Stopping VM {0} before deletion", virtualMachineId.toString());
                String stopTask = pveApi.stopQemuMachine(datacenterNode, virtualMachineId);
                JSONObject stopResult = pveApi.waitForTaskToFinish(datacenterNode, stopTask);
                LOGGER.log(Level.INFO, "VM {0} stop finished: {1}", new Object[] {
                    virtualMachineId.toString(), VirtualMachineLauncher.describe(stopResult)
                });
            }
            String deleteTask = pveApi.deleteQemuMachine(datacenterNode, virtualMachineId);
            LOGGER.log(Level.INFO, "VM {0} deletion initiated (task: {1})", new Object[] {
                virtualMachineId.toString(), deleteTask
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Interrupted while deleting VM " + virtualMachineId, e);
        } catch (LoginException | IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to stop/delete VM " + virtualMachineId + " from Proxmox", e);
        }
    }

    @Override
    public VirtualMachineSlaveComputer createComputer() {
        // The computer implements the "Before every job" revert in taskAccepted().
        return new VirtualMachineSlaveComputer(this);
    }

    /**
     * Called when the agent is deleted. A virtual machine that the cloud cloned for this agent is
     * stopped and deleted; one that was assigned by hand is left alone.
     */
    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        if (!isProvisionedByCloud()) {
            return;
        }
        Datacenter datacenter = Datacenter.findByDescription(datacenterDescription);
        if (datacenter == null) {
            listener.getLogger().println("Warning: Cannot terminate - datacenter not found: " + datacenterDescription);
            return;
        }
        Computer computer = toComputer();
        if (computer == null) {
            listener.getLogger().println("Warning: Cannot terminate - computer is null");
            return;
        }
        listener.getLogger().println("Terminating VM " + virtualMachineId + " through datacenter");
        datacenter.terminate(computer);
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Agent virtual machine running on a Proxmox datacenter";
        }

        @Override
        public boolean isInstantiable() {
            return true;
        }

        /**
         * Agents can be configured by users with {@link Computer#CONFIGURE} (existing agent)
         * or {@link Computer#CREATE} (new agent), not only by administrators.
         */
        private static void checkConfigurePermission(Computer computer) {
            if (computer != null) {
                computer.checkPermission(Computer.CONFIGURE);
            } else {
                Jenkins.get().checkPermission(Computer.CREATE);
            }
        }

        @POST
        public ListBoxModel doFillDatacenterDescriptionItems(@AncestorInPath Computer computer) {
            checkConfigurePermission(computer);
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            for (Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof Datacenter) {
                    // Two datacenters on one host (different credentials) must stay distinguishable.
                    items.add(((Datacenter) cloud).getDatacenterDescription());
                }
            }
            return items;
        }

        @POST
        public ListBoxModel doFillDatacenterNodeItems(
                @AncestorInPath Computer computer,
                @QueryParameter("datacenterDescription") String datacenterDescription) {
            checkConfigurePermission(computer);
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter != null) {
                for (String node : datacenter.getNodes()) {
                    items.add(node);
                }
            }
            return items;
        }

        @POST
        public ListBoxModel doFillVirtualMachineIdItems(
                @AncestorInPath Computer computer,
                @QueryParameter("datacenterDescription") String datacenterDescription,
                @QueryParameter("datacenterNode") String datacenterNode) {
            checkConfigurePermission(computer);
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter != null) {
                Map<Integer, String> machines = datacenter.getQemuMachines(datacenterNode);
                for (Map.Entry<Integer, String> me : machines.entrySet()) {
                    items.add(me.getValue() + " (" + me.getKey() + ")", me.getKey().toString());
                }
            }
            return items;
        }

        @POST
        public ListBoxModel doFillSnapshotNameItems(
                @AncestorInPath Computer computer,
                @QueryParameter("datacenterDescription") String datacenterDescription,
                @QueryParameter("datacenterNode") String datacenterNode,
                @QueryParameter("virtualMachineId") String virtualMachineId) {
            checkConfigurePermission(computer);
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter != null && virtualMachineId != null && !virtualMachineId.isEmpty()) {
                try {
                    int vmid = Integer.parseInt(virtualMachineId);
                    for (String snapshot : datacenter.getQemuMachineSnapshots(datacenterNode, vmid)) {
                        items.add(snapshot);
                    }
                } catch (NumberFormatException e) {
                    // invalid virtualMachineId input; skip populating snapshots
                }
            }
            return items;
        }

        /**
         * Checks that the selected snapshot exists. This deliberately does not roll back the
         * virtual machine, because that would destroy the state of a possibly running agent.
         */
        @POST
        public FormValidation doVerifySnapshot(
                @AncestorInPath Computer computer,
                @QueryParameter String datacenterDescription,
                @QueryParameter String datacenterNode,
                @QueryParameter String virtualMachineId,
                @QueryParameter String snapshotName) {
            checkConfigurePermission(computer);
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter == null) return FormValidation.error("Datacenter not found!");
            if (Util.fixEmptyAndTrim(datacenterNode) == null) return FormValidation.error("No node selected");
            int vmid;
            try {
                vmid = Integer.parseInt(Util.fixNull(virtualMachineId).trim());
            } catch (NumberFormatException e) {
                return FormValidation.error("No virtual machine selected");
            }
            if (Util.fixEmptyAndTrim(snapshotName) == null) return FormValidation.error("No snapshot selected");
            if ("current".equals(snapshotName)) {
                return FormValidation.ok("'current' is selected, so the virtual machine will not be reverted");
            }
            try {
                List<String> snapshots = datacenter.proxmoxInstance().getQemuMachineSnapshots(datacenterNode, vmid);
                if (snapshots.contains(snapshotName)) {
                    return FormValidation.ok("Snapshot '" + snapshotName + "' exists and can be rolled back to");
                }
                return FormValidation.error(
                        "Snapshot '" + snapshotName + "' does not exist on virtual machine " + vmid);
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                return FormValidation.error("Request failed: " + e.getMessage());
            }
        }

        @POST
        public FormValidation doStartVM(
                @QueryParameter String datacenterDescription,
                @QueryParameter String datacenterNode,
                @QueryParameter Integer virtualMachineId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter == null) return FormValidation.error("Datacenter not found!");
            try {
                Connector pveApi = datacenter.proxmoxInstance();
                if (pveApi.isQemuMachineRunning(datacenterNode, virtualMachineId)) {
                    return FormValidation.warning("VM " + virtualMachineId + " is already running");
                }
                String taskId = pveApi.startQemuMachine(datacenterNode, virtualMachineId);
                return FormValidation.ok("Start task initiated with ID: " + taskId);
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                return FormValidation.error("Start VM Failed: " + e.getMessage());
            }
        }

        @POST
        public FormValidation doStopVM(
                @QueryParameter String datacenterDescription,
                @QueryParameter String datacenterNode,
                @QueryParameter Integer virtualMachineId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter == null) return FormValidation.error("Datacenter not found!");
            try {
                Connector pveApi = datacenter.proxmoxInstance();
                if (!pveApi.isQemuMachineRunning(datacenterNode, virtualMachineId)) {
                    return FormValidation.warning("VM " + virtualMachineId + " is already stopped");
                }
                String taskId = pveApi.stopQemuMachine(datacenterNode, virtualMachineId);
                return FormValidation.ok("Stop task initiated with ID: " + taskId);
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                return FormValidation.error("Stop VM Failed: " + e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckVMStatus(
                @QueryParameter String datacenterDescription,
                @QueryParameter String datacenterNode,
                @QueryParameter Integer virtualMachineId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter == null) return FormValidation.error("Datacenter not found!");
            try {
                JSONObject status = datacenter.proxmoxInstance().getQemuMachineStatus(datacenterNode, virtualMachineId);
                String vmStatus = status.optString("status", "unknown");
                boolean isRunning = "running".equals(vmStatus);

                StringBuilder statusMessage = new StringBuilder();
                statusMessage.append("VM ").append(virtualMachineId).append(" Status: ").append(vmStatus);
                if (isRunning) {
                    statusMessage.append(" (Running)");
                    if (status.has("uptime")) {
                        statusMessage.append(", Uptime: ").append(status.optLong("uptime")).append("s");
                    }
                    if (status.has("cpu")) {
                        statusMessage.append(", CPU: ").append(String.format("%.2f%%", status.optDouble("cpu") * 100));
                    }
                    long memBytes = status.optLong("mem", -1);
                    long maxmemBytes = status.optLong("maxmem", -1);
                    if (memBytes >= 0 && maxmemBytes > 0) {
                        statusMessage
                                .append(", Memory: ")
                                .append(String.format(
                                        "%.1f%% (%d MB / %d MB)",
                                        (double) memBytes / maxmemBytes * 100,
                                        memBytes / (1024 * 1024),
                                        maxmemBytes / (1024 * 1024)));
                    }
                } else {
                    statusMessage.append(" (Stopped)");
                }

                return FormValidation.ok(statusMessage.toString());
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
            } catch (IOException | RuntimeException e) {
                return FormValidation.error("Status Check Failed: " + e.getMessage());
            }
        }

        private Datacenter getDatacenterByDescription(String datacenterDescription) {
            return Datacenter.findByDescription(datacenterDescription);
        }
    }
}
