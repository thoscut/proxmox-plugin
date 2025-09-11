package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class ProxmoxCloudSlaveTemplate extends AbstractDescribableImpl<ProxmoxCloudSlaveTemplate> {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxCloudSlaveTemplate.class.getName());
    
    private final String templateName;
    private final String labels;
    private final String remoteFS;
    private final String numExecutors;
    private final String datacenterNode;
    private final String templateVmId;
    private final String snapshotName;
    private final int instanceCap;
    private final int maxIdleMinutes;
    private final boolean startVM;
    private final int startupWaitingPeriodSeconds;
    private final ComputerLauncher launcher;
    private final RetentionStrategy<?> retentionStrategy;
    private final List<? extends NodeProperty<?>> nodeProperties;
    
    private transient Set<LabelAtom> labelSet;
    private transient AtomicInteger currentlyProvisioning = new AtomicInteger(0);

    @DataBoundConstructor
    public ProxmoxCloudSlaveTemplate(String templateName,
                                   String labels,
                                   String remoteFS,
                                   String numExecutors,
                                   String datacenterNode,
                                   String templateVmId,
                                   String snapshotName,
                                   int instanceCap,
                                   int maxIdleMinutes,
                                   boolean startVM,
                                   int startupWaitingPeriodSeconds,
                                   ComputerLauncher launcher,
                                   RetentionStrategy<?> retentionStrategy,
                                   List<? extends NodeProperty<?>> nodeProperties) {
        this.templateName = templateName;
        this.labels = labels;
        this.remoteFS = remoteFS;
        this.numExecutors = numExecutors;
        this.datacenterNode = datacenterNode;
        this.templateVmId = templateVmId;
        this.snapshotName = snapshotName;
        this.instanceCap = instanceCap;
        this.maxIdleMinutes = maxIdleMinutes;
        this.startVM = startVM;
        this.startupWaitingPeriodSeconds = startupWaitingPeriodSeconds;
        this.launcher = launcher;
        this.retentionStrategy = retentionStrategy;
        this.nodeProperties = nodeProperties;
    }

    public boolean canProvision(Label label) {
        return label == null || getLabelAtoms().contains(label);
    }

    private Set<LabelAtom> getLabelAtoms() {
        if (labelSet == null) {
            labelSet = Label.parse(labels);
        }
        return labelSet;
    }

    public VirtualMachineSlave provision(Datacenter datacenter) throws Exception {
        if (getCurrentSlaveCount() >= getInstanceCap()) {
            throw new IllegalStateException("Instance cap reached for template: " + templateName);
        }

        currentlyProvisioning.incrementAndGet();
        try {
            String cloneName = generateCloneName();
            
            Connector proxmoxApi = datacenter.proxmoxInstance();
            Integer clonedVmId = cloneVmFromTemplate(proxmoxApi, cloneName);
            
            if (startVM) {
                startClonedVm(proxmoxApi, clonedVmId);
            }

            VirtualMachineSlave slave = new VirtualMachineSlave(
                cloneName,
                "Proxmox Cloud Slave from template " + templateName,
                remoteFS,
                numExecutors,
                Node.Mode.NORMAL,
                labels,
                launcher,
                retentionStrategy,
                nodeProperties,
                datacenter.getDatacenterDescription(),
                datacenterNode,
                clonedVmId,
                snapshotName,
                startVM,
                startupWaitingPeriodSeconds,
                RevertPolicy.NEVER
            );

            return slave;
        } finally {
            currentlyProvisioning.decrementAndGet();
        }
    }

    private String generateCloneName() {
        return templateName + "-" + System.currentTimeMillis();
    }

    private Integer cloneVmFromTemplate(Connector proxmoxApi, String cloneName) throws LoginException {
        Integer templateVmIdInt = Integer.parseInt(templateVmId);
        
        Integer nextVmId = getNextAvailableVmId(proxmoxApi);
        
        String taskStatus = proxmoxApi.cloneQemuMachine(datacenterNode, templateVmIdInt, nextVmId, cloneName);
        LOGGER.log(Level.INFO, "Cloned VM {0} from template {1}: {2}", 
                  new Object[]{nextVmId, templateVmIdInt, taskStatus});
        
        if (snapshotName != null && !snapshotName.isEmpty()) {
            String rollbackStatus = proxmoxApi.rollbackQemuMachineSnapshot(datacenterNode, nextVmId, snapshotName);
            LOGGER.log(Level.INFO, "Reverted VM {0} to snapshot {1}: {2}", 
                      new Object[]{nextVmId, snapshotName, rollbackStatus});
        }
        
        return nextVmId;
    }

    private Integer getNextAvailableVmId(Connector proxmoxApi) throws LoginException {
        HashMap<String, Integer> existingVms = proxmoxApi.getQemuMachines(datacenterNode);
        int vmId = 1000;
        
        while (existingVms.containsValue(vmId)) {
            vmId++;
        }
        
        return vmId;
    }

    private void startClonedVm(Connector proxmoxApi, Integer vmId) throws LoginException {
        String startStatus = proxmoxApi.startQemuMachine(datacenterNode, vmId);
        LOGGER.log(Level.INFO, "Started VM {0}: {1}", new Object[]{vmId, startStatus});
        
        if (startupWaitingPeriodSeconds > 0) {
            try {
                Thread.sleep(startupWaitingPeriodSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Interrupted while waiting for VM startup", e);
            }
        }
    }

    private int getCurrentSlaveCount() {
        int count = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof VirtualMachineSlave) {
                VirtualMachineSlave vmSlave = (VirtualMachineSlave) node;
                if (templateName.equals(getTemplateNameFromSlave(vmSlave))) {
                    count++;
                }
            }
        }
        return count + currentlyProvisioning.get();
    }

    private String getTemplateNameFromSlave(VirtualMachineSlave slave) {
        String slaveName = slave.getNodeName();
        int dashIndex = slaveName.lastIndexOf('-');
        return dashIndex > 0 ? slaveName.substring(0, dashIndex) : slaveName;
    }

    public String getTemplateName() { return templateName; }
    public String getLabels() { return labels; }
    public String getRemoteFS() { return remoteFS; }
    public String getNumExecutors() { return numExecutors; }
    public String getDatacenterNode() { return datacenterNode; }
    public String getTemplateVmId() { return templateVmId; }
    public String getSnapshotName() { return snapshotName; }
    public int getInstanceCap() { return instanceCap; }
    public int getMaxIdleMinutes() { return maxIdleMinutes; }
    public boolean getStartVM() { return startVM; }
    public int getStartupWaitingPeriodSeconds() { return startupWaitingPeriodSeconds; }
    public ComputerLauncher getLauncher() { return launcher; }
    public RetentionStrategy<?> getRetentionStrategy() { return retentionStrategy; }
    public List<? extends NodeProperty<?>> getNodeProperties() { return nodeProperties; }

    @Extension
    public static class DescriptorImpl extends Descriptor<ProxmoxCloudSlaveTemplate> {

        @Override
        public String getDisplayName() {
            return "Proxmox Cloud Slave Template";
        }

        public ListBoxModel doFillDatacenterNodeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            
            for (Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof Datacenter) {
                    Datacenter datacenter = (Datacenter) cloud;
                    for (String node : datacenter.getNodes()) {
                        items.add(node);
                    }
                }
            }
            return items;
        }

        public ListBoxModel doFillTemplateVmIdItems(@QueryParameter String datacenterNode) {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            
            if (datacenterNode != null && !datacenterNode.isEmpty()) {
                for (Cloud cloud : Jenkins.get().clouds) {
                    if (cloud instanceof Datacenter) {
                        Datacenter datacenter = (Datacenter) cloud;
                        HashMap<String, Integer> machines = datacenter.getQemuMachines(datacenterNode);
                        for (HashMap.Entry<String, Integer> entry : machines.entrySet()) {
                            items.add(entry.getKey(), entry.getValue().toString());
                        }
                    }
                }
            }
            return items;
        }

        public ListBoxModel doFillSnapshotNameItems(@QueryParameter String datacenterNode, 
                                                   @QueryParameter String templateVmId) {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            
            if (datacenterNode != null && !datacenterNode.isEmpty() && 
                templateVmId != null && !templateVmId.isEmpty()) {
                try {
                    Integer vmId = Integer.parseInt(templateVmId);
                    for (Cloud cloud : Jenkins.get().clouds) {
                        if (cloud instanceof Datacenter) {
                            Datacenter datacenter = (Datacenter) cloud;
                            List<String> snapshots = datacenter.getQemuMachineSnapshots(datacenterNode, vmId);
                            for (String snapshot : snapshots) {
                                items.add(snapshot);
                            }
                        }
                    }
                } catch (NumberFormatException e) {
                    // Invalid VM ID
                }
            }
            return items;
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String value) {
            try {
                int cap = Integer.parseInt(value);
                if (cap < 0) {
                    return FormValidation.error("Instance cap must be non-negative");
                }
                if (cap > 100) {
                    return FormValidation.warning("Large instance cap may consume significant resources");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Instance cap must be a valid integer");
            }
        }

        public FormValidation doCheckMaxIdleMinutes(@QueryParameter String value) {
            try {
                int minutes = Integer.parseInt(value);
                if (minutes < 0) {
                    return FormValidation.error("Max idle minutes must be non-negative");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Max idle minutes must be a valid integer");
            }
        }

        @POST  
        public FormValidation doCheckTemplateVMStatus(
                @QueryParameter String datacenterNode,
                @QueryParameter String templateVmId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            
            if (datacenterNode == null || datacenterNode.isEmpty()) {
                return FormValidation.error("Please select a datacenter node");
            }
            if (templateVmId == null || templateVmId.isEmpty()) {
                return FormValidation.error("Please select a template VM ID");
            }
            
            try {
                Integer vmId = Integer.parseInt(templateVmId);
                for (Cloud cloud : Jenkins.get().clouds) {
                    if (cloud instanceof Datacenter) {
                        Datacenter datacenter = (Datacenter) cloud;
                        Connector pveApi = datacenter.proxmoxInstance();
                        
                        boolean isRunning = pveApi.isQemuMachineRunning(datacenterNode, vmId);
                        JSONObject status = pveApi.getQemuMachineStatus(datacenterNode, vmId);
                        String vmStatus = status.getString("status");
                        String uptime = status.optString("uptime", "N/A");
                        
                        StringBuilder statusMessage = new StringBuilder();
                        statusMessage.append("Template VM ").append(vmId).append(" Status: ").append(vmStatus);
                        if (isRunning) {
                            statusMessage.append(" (Running)");
                            if (!uptime.equals("N/A")) {
                                statusMessage.append(", Uptime: ").append(uptime).append("s");
                            }
                        } else {
                            statusMessage.append(" (Stopped)");
                        }
                        
                        return FormValidation.ok(statusMessage.toString());
                    }
                }
                return FormValidation.error("No Proxmox datacenter found");
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid VM ID format");
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
            } catch (Exception e) {
                return FormValidation.error("Status Check Failed: " + e.getMessage());
            }
        }
        
        public List<Descriptor<RetentionStrategy<?>>> getRetentionStrategyDescriptors() {
            return Jenkins.get().getDescriptorList(RetentionStrategy.class);
        }
    }
}