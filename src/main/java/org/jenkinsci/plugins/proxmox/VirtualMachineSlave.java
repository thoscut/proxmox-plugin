package org.jenkinsci.plugins.proxmox;

import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;

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
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class VirtualMachineSlave extends Slave {

    private static final long serialVersionUID = 1L;

    private String datacenterDescription;
    private String datacenterNode;
    private String snapshotName;
    private Integer virtualMachineId;
    private Boolean startVM;
    private int startupWaitingPeriodSeconds;
    private RevertPolicy revertPolicy;

    @DataBoundConstructor
    public VirtualMachineSlave(
            String name,
            String nodeDescription,
            String remoteFS,
            String numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher delegateLauncher,
            RetentionStrategy retentionStrategy,
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
                nodeDescription,
                remoteFS,
                numExecutors,
                mode,
                labelString,
                new VirtualMachineLauncher(
                        delegateLauncher,
                        datacenterDescription,
                        datacenterNode,
                        virtualMachineId,
                        snapshotName,
                        startVM,
                        startupWaitingPeriodSeconds,
                        revertPolicy),
                retentionStrategy,
                ofNullable(nodeProperties).orElse(emptyList()));
        this.datacenterDescription = datacenterDescription;
        this.datacenterNode = datacenterNode;
        this.virtualMachineId = virtualMachineId;
        this.snapshotName = snapshotName;
        this.startVM = startVM;
        this.startupWaitingPeriodSeconds = startupWaitingPeriodSeconds;
        this.revertPolicy = revertPolicy;
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
        if (launcher != null && !(launcher instanceof VirtualMachineLauncher)) {
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
    public Computer createComputer() {
        // TODO: Not sure if this is needed, could be able to use this to reset to snapshots
        // TODO: as a computer is required for a job.
        return new VirtualMachineSlaveComputer(this);
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Agent virtual machine running on a Proxmox datacenter.";
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

        public ListBoxModel doFillDatacenterDescriptionItems(@AncestorInPath Computer computer) {
            checkConfigurePermission(computer);
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            for (Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof Datacenter) {
                    // TODO: Possibly add the `datacenterDescription` as the `displayName` and `value`
                    // (http://javadoc.jenkins-ci.org/hudson/util/ListBoxModel.html)
                    // Add by `display name` and then the `value`
                    items.add(((Datacenter) cloud).getHostname(), ((Datacenter) cloud).getDatacenterDescription());
                }
            }
            return items;
        }

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
        public FormValidation doTestRollback(
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

        private Datacenter getDatacenterByDescription(String datacenterDescription) {
            if (datacenterDescription != null && !datacenterDescription.equals("")) {
                for (Cloud cloud : Jenkins.get().clouds) {
                    if (cloud instanceof Datacenter
                            && datacenterDescription.equals(((Datacenter) cloud).getDatacenterDescription())) {
                        return (Datacenter) cloud;
                    }
                }
            }
            return null;
        }
    }
}
