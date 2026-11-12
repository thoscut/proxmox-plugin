package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Slave;
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
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * An agent that was bound by hand to an existing Proxmox virtual machine. The virtual machine is
 * started, reverted and shut down for the agent, but it is never deleted.
 *
 * <p>Agents that the cloud provisions on demand are {@link ProvisionedVirtualMachineSlave}s. This
 * class deliberately is no {@link hudson.slaves.AbstractCloudSlave}: Configuration as Code neither
 * exports cloud agents nor removes them when they are missing from the applied configuration.
 */
public class VirtualMachineSlave extends Slave implements ProxmoxAgent {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(VirtualMachineSlave.class.getName());

    private String datacenterDescription;
    private String datacenterNode;
    private String snapshotName;
    private Integer virtualMachineId;
    private Boolean startVM;
    private int startupWaitingPeriodSeconds;
    private RevertPolicy revertPolicy;

    /**
     * Only read from the configuration of older versions, in which provisioned agents were instances
     * of this class and marked by this id. See {@link #readResolve()}.
     */
    @Deprecated
    private transient ProvisioningActivity.Id provisioningId;

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
        setNumExecutors(parseExecutors(numExecutors));
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

    /** @return the number of executors that was entered in a form; one if it is no number. */
    static int parseExecutors(String numExecutors) {
        Number parsed = Util.tryParseNumber(numExecutors, 1);
        return parsed == null ? 1 : parsed.intValue();
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

    @Override
    public String getDatacenterDescription() {
        return datacenterDescription;
    }

    @Override
    public String getDatacenterNode() {
        return datacenterNode;
    }

    @Override
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

    @Override
    public VirtualMachineSlaveComputer createComputer() {
        // The computer implements the revert policies "Before every job" and "After every job".
        return new VirtualMachineSlaveComputer(this);
    }

    /**
     * Older versions stored provisioned agents as instances of this class. They own their cloned
     * virtual machine, so they are turned into {@link ProvisionedVirtualMachineSlave}s when loaded.
     */
    @Override
    protected Object readResolve() {
        Object resolved = super.readResolve();
        if (provisioningId == null) {
            return resolved;
        }
        try {
            return ProvisionedVirtualMachineSlave.migrate(this, provisioningId);
        } catch (IOException | Descriptor.FormException e) {
            // Kept as an agent that never deletes its virtual machine, which is the safe side.
            LOGGER.log(Level.WARNING, "Could not load " + getNodeName() + " as a provisioned agent", e);
            return resolved;
        }
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
            // The node is optional: the datacenter knows where a virtual machine is.
            items.add("[Automatic]", "");
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
