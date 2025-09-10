package org.jenkinsci.plugins.proxmox;

import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;

import hudson.Extension;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;
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
        return ((VirtualMachineLauncher) getLauncher()).getLauncher();
    }

    @Override
    public Computer createComputer() {
        // TODO: Not sure if this is needed, could be able to use this to reset to snapshots
        // TODO: as a computer is required for a job.
        return new VirtualMachineSlaveComputer(this);
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        private String datacenterDescription;
        private String datacenterNode;
        private Integer virtualMachineId;
        private String snapshotName;
        private Boolean startVM;
        private RevertPolicy revertPolicy;

        public DescriptorImpl() {
            load();
        }

        public String getDisplayName() {
            return "Agent virtual machine running on a Proxmox datacenter.";
        }

        @Override
        public boolean isInstantiable() {
            return true;
        }

        public ListBoxModel doFillDatacenterDescriptionItems() {
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
                @QueryParameter("datacenterDescription") String datacenterDescription) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
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
                @QueryParameter("datacenterDescription") String datacenterDescription,
                @QueryParameter("datacenterNode") String datacenterNode) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter != null) {
                HashMap<String, Integer> machines = datacenter.getQemuMachines(datacenterNode);
                for (Map.Entry<String, Integer> me : machines.entrySet()) {
                    items.add(me.getKey().toString(), me.getValue().toString());
                }
            }
            return items;
        }

        public ListBoxModel doFillSnapshotNameItems(
                @QueryParameter("datacenterDescription") String datacenterDescription,
                @QueryParameter("datacenterNode") String datacenterNode,
                @QueryParameter("virtualMachineId") String virtualMachineId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter != null && virtualMachineId != null && virtualMachineId.length() != 0) {
                for (String snapshot :
                        datacenter.getQemuMachineSnapshots(datacenterNode, Integer.parseInt(virtualMachineId))) {
                    items.add(snapshot);
                }
            }
            return items;
        }

        public String getDatacenterDescription() {
            return datacenterDescription;
        }

        public String getDatecenterNode() {
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

        public RevertPolicy getRevertPolicy() {
            return revertPolicy;
        }

        @POST
        public FormValidation doTestRollback(
                @QueryParameter String datacenterDescription,
                @QueryParameter String datacenterNode,
                @QueryParameter Integer virtualMachineId,
                @QueryParameter String snapshotName) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            Datacenter datacenter = getDatacenterByDescription(datacenterDescription);
            if (datacenter == null) return FormValidation.error("Datacenter not found!");
            Connector pveApi = datacenter.proxmoxInstance();
            try {
                String taskStatus = pveApi.rollbackQemuMachineSnapshot(datacenterNode, virtualMachineId, snapshotName);
                return FormValidation.ok("Returned: " + taskStatus);
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
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
            Connector pveApi = datacenter.proxmoxInstance();
            try {
                boolean isRunning = pveApi.isQemuMachineRunning(datacenterNode, virtualMachineId);
                if (isRunning) {
                    return FormValidation.warning("VM " + virtualMachineId + " is already running");
                }
                String taskId = pveApi.startQemuMachine(datacenterNode, virtualMachineId);
                return FormValidation.ok("Start task initiated with ID: " + taskId);
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
            } catch (Exception e) {
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
            Connector pveApi = datacenter.proxmoxInstance();
            try {
                boolean isRunning = pveApi.isQemuMachineRunning(datacenterNode, virtualMachineId);
                if (!isRunning) {
                    return FormValidation.warning("VM " + virtualMachineId + " is already stopped");
                }
                String taskId = pveApi.stopQemuMachine(datacenterNode, virtualMachineId);
                return FormValidation.ok("Stop task initiated with ID: " + taskId);
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
            } catch (Exception e) {
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
            Connector pveApi = datacenter.proxmoxInstance();
            try {
                boolean isRunning = pveApi.isQemuMachineRunning(datacenterNode, virtualMachineId);
                JSONObject status = pveApi.getQemuMachineStatus(datacenterNode, virtualMachineId);
                String vmStatus = status.getString("status");
                String uptime = status.optString("uptime", "N/A");
                String cpu = status.optString("cpu", "N/A");
                String mem = status.optString("mem", "N/A");
                String maxmem = status.optString("maxmem", "N/A");
                
                StringBuilder statusMessage = new StringBuilder();
                statusMessage.append("VM ").append(virtualMachineId).append(" Status: ").append(vmStatus);
                if (isRunning) {
                    statusMessage.append(" (Running)");
                    if (!uptime.equals("N/A")) {
                        statusMessage.append(", Uptime: ").append(uptime).append("s");
                    }
                    if (!cpu.equals("N/A")) {
                        statusMessage.append(", CPU: ").append(String.format("%.2f%%", Double.parseDouble(cpu) * 100));
                    }
                    if (!mem.equals("N/A") && !maxmem.equals("N/A")) {
                        long memBytes = Long.parseLong(mem);
                        long maxmemBytes = Long.parseLong(maxmem);
                        double memUsage = (double) memBytes / maxmemBytes * 100;
                        statusMessage.append(", Memory: ").append(String.format("%.1f%% (%d MB / %d MB)", 
                            memUsage, memBytes / (1024 * 1024), maxmemBytes / (1024 * 1024)));
                    }
                } else {
                    statusMessage.append(" (Stopped)");
                }
                
                return FormValidation.ok(statusMessage.toString());
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
            } catch (Exception e) {
                return FormValidation.error("Status Check Failed: " + e.getMessage());
            }
        }

        private Datacenter getDatacenterByDescription(String datacenterDescription) {
            if (datacenterDescription != null && !datacenterDescription.equals("")) {
                for (Cloud cloud : Jenkins.get().clouds) {
                    if (cloud instanceof Datacenter
                            && ((Datacenter) cloud).getDatacenterDescription().equals(datacenterDescription)) {
                        return (Datacenter) cloud;
                    }
                }
            }
            return null;
        }
    }
}
