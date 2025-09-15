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
import java.util.Map;
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
    private final boolean linkedClone;
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
                                   boolean linkedClone,
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
        this.linkedClone = linkedClone;
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
        // For backward compatibility - generate a name if not provided
        return provision(datacenter, null);
    }

    public VirtualMachineSlave provision(Datacenter datacenter, String plannedNodeName) throws Exception {
        int currentCount = getCurrentSlaveCount();
        int instanceCap = getInstanceCap();
        if (currentCount >= instanceCap) {
            LOGGER.log(Level.WARNING, "Instance cap reached for template {0}: {1}/{2} slaves running", 
                      new Object[]{templateName, currentCount, instanceCap});
            throw new IllegalStateException("Instance cap reached for template: " + templateName + 
                                          " (" + currentCount + "/" + instanceCap + " slaves running)");
        }
        
        LOGGER.log(Level.INFO, "Provisioning new slave for template {0}: {1}/{2} slaves currently running", 
                  new Object[]{templateName, currentCount, instanceCap});

        if (currentlyProvisioning == null) {
            currentlyProvisioning = new AtomicInteger(0);
        }
        currentlyProvisioning.incrementAndGet();
        try {
            Connector proxmoxApi = datacenter.proxmoxInstance();
            // Use the planned node name if provided, otherwise generate one
            String cloneName = (plannedNodeName != null) ? plannedNodeName : generateCloneName(proxmoxApi);
            Integer clonedVmId = cloneVmFromTemplate(proxmoxApi, cloneName);
            
            if (startVM) {
                startClonedVm(proxmoxApi, clonedVmId, cloneName);
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
            if (currentlyProvisioning != null) {
                currentlyProvisioning.decrementAndGet();
            }
        }
    }

    private String generateCloneName(Connector proxmoxApi) throws LoginException {
        // Get the actual template VM name from Proxmox using the template VM ID
        HashMap<String, Integer> machines = proxmoxApi.getQemuMachines(datacenterNode);
        Integer templateVmIdInt = Integer.parseInt(templateVmId);
        
        String actualTemplateName = null;
        for (Map.Entry<String, Integer> entry : machines.entrySet()) {
            if (entry.getValue().equals(templateVmIdInt)) {
                actualTemplateName = entry.getKey();
                break;
            }
        }
        
        // Fallback to configured template name if not found
        if (actualTemplateName == null) {
            LOGGER.log(Level.WARNING, "Could not find template VM with ID {0}, using configured template name: {1}", 
                      new Object[]{templateVmId, templateName});
            actualTemplateName = templateName;
        }
        
        return actualTemplateName + "-" + System.currentTimeMillis();
    }

    private Integer cloneVmFromTemplate(Connector proxmoxApi, String cloneName) throws LoginException {
        Integer templateVmIdInt = Integer.parseInt(templateVmId);
        
        // Get template name for better logging
        HashMap<String, Integer> machines = proxmoxApi.getQemuMachines(datacenterNode);
        String templateVmName = null;
        for (Map.Entry<String, Integer> entry : machines.entrySet()) {
            if (entry.getValue().equals(templateVmIdInt)) {
                templateVmName = entry.getKey();
                break;
            }
        }
        // Fallback if template name not found
        if (templateVmName == null) {
            templateVmName = "VM-" + templateVmIdInt;
        }
        
        // Check if template VM is running - cloning running VMs can cause issues
        boolean templateIsRunning = proxmoxApi.isQemuMachineRunning(datacenterNode, templateVmIdInt);
        if (templateIsRunning) {
            String errorMessage = "Cannot clone from running template VM " + templateVmName + " (ID: " + templateVmIdInt + 
                                  "). Template VM must be stopped before cloning to avoid data corruption and ensure consistent clones.";
            LOGGER.log(Level.SEVERE, errorMessage);
            throw new IllegalStateException(errorMessage);
        }
        
        LOGGER.log(Level.FINE, "Template VM {0} (ID: {1}) is stopped, proceeding with clone operation", 
                  new Object[]{templateVmName, templateVmIdInt});
        
        // Determine the actual snapshot parameter to use
        String actualSnapshotParam = null;
        if (snapshotName != null && !snapshotName.isEmpty()) {
            if ("current".equals(snapshotName)) {
                // For "current", check if VM has any snapshots at all
                List<String> availableSnapshots = proxmoxApi.getQemuMachineSnapshots(datacenterNode, templateVmIdInt);
                if (availableSnapshots.isEmpty()) {
                    // No snapshots exist, clone current state without snapshot parameter
                    actualSnapshotParam = null;
                    LOGGER.log(Level.FINE, "Template VM {0} (ID: {1}) has no snapshots, cloning current state directly", 
                              new Object[]{templateVmName, templateVmIdInt});
                } else {
                    // Snapshots exist, use "current" to clone from current state
                    actualSnapshotParam = "current";
                    LOGGER.log(Level.FINE, "Using 'current' snapshot for template VM {0} (ID: {1}) with {2} available snapshots", 
                              new Object[]{templateVmName, templateVmIdInt, availableSnapshots.size()});
                }
            } else {
                // Validate specific snapshot exists
                List<String> availableSnapshots = proxmoxApi.getQemuMachineSnapshots(datacenterNode, templateVmIdInt);
                if (!availableSnapshots.contains(snapshotName)) {
                    String errorMessage = "Snapshot '" + snapshotName + "' does not exist on template VM " + templateVmName + 
                                          " (ID: " + templateVmIdInt + "). Available snapshots: " + availableSnapshots + 
                                          " (Note: 'current' refers to the current state and is always available)";
                    LOGGER.log(Level.SEVERE, errorMessage);
                    throw new IllegalStateException(errorMessage);
                }
                actualSnapshotParam = snapshotName;
                LOGGER.log(Level.FINE, "Using snapshot '{0}' for template VM {1} (ID: {2})", 
                          new Object[]{snapshotName, templateVmName, templateVmIdInt});
            }
        }
        
        Integer nextVmId = getNextAvailableVmId(proxmoxApi);
        
        String taskStatus = null;
        boolean usedLinkedClone = false;
        
        // Try linked clone first if requested
        if (linkedClone) {
            try {
                taskStatus = proxmoxApi.cloneQemuMachine(datacenterNode, templateVmIdInt, nextVmId, cloneName, false, actualSnapshotParam);
                usedLinkedClone = true;
                String snapshotInfo = (actualSnapshotParam != null) ? " from snapshot '" + actualSnapshotParam + "'" : " from current state";
                LOGGER.log(Level.INFO, "Successfully created linked clone VM {0} (ID: {1}) from template {2} (ID: {3}){4}: {5}", 
                          new Object[]{cloneName, nextVmId, templateVmName, templateVmIdInt, snapshotInfo, taskStatus});
            } catch (RuntimeException e) {
                // Check if the error is due to linked clone not being supported
                if (e.getMessage() != null && e.getMessage().contains("Linked clone feature is not supported")) {
                    LOGGER.log(Level.WARNING, "Linked clone not supported for template {0} (ID: {1}), falling back to full clone: {2}", 
                              new Object[]{templateVmName, templateVmIdInt, e.getMessage()});
                    // Will fall through to full clone attempt
                } else {
                    // Re-throw other types of runtime exceptions
                    throw e;
                }
            }
        }
        
        // If linked clone wasn't requested or failed, try full clone
        if (taskStatus == null) {
            taskStatus = proxmoxApi.cloneQemuMachine(datacenterNode, templateVmIdInt, nextVmId, cloneName, true, actualSnapshotParam);
            String snapshotInfo = (actualSnapshotParam != null) ? " from snapshot '" + actualSnapshotParam + "'" : " from current state";
            LOGGER.log(Level.INFO, "Created full clone VM {0} (ID: {1}) from template {2} (ID: {3}){4}: {5}", 
                      new Object[]{cloneName, nextVmId, templateVmName, templateVmIdInt, snapshotInfo, taskStatus});
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

    private void startClonedVm(Connector proxmoxApi, Integer vmId, String cloneName) throws LoginException {
        String startStatus = proxmoxApi.startQemuMachine(datacenterNode, vmId);
        LOGGER.log(Level.INFO, "Started VM {0} (ID: {1}): {2}", new Object[]{cloneName, vmId, startStatus});
        
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
        int onlineCount = 0;
        int offlineCount = 0;
        int tempOfflineCount = 0;
        int totalMatchingNodes = 0;
        
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof VirtualMachineSlave) {
                VirtualMachineSlave vmSlave = (VirtualMachineSlave) node;
                if (templateName.equals(getTemplateNameFromSlave(vmSlave))) {
                    totalMatchingNodes++;
                    if (vmSlave.getComputer() != null) {
                        if (!vmSlave.getComputer().isOffline()) {
                            onlineCount++;
                        } else if (vmSlave.getComputer().isTemporarilyOffline()) {
                            tempOfflineCount++;
                        } else {
                            offlineCount++;
                        }
                    } else {
                        offlineCount++;
                    }
                }
            }
        }
        
        int currentlyProvisioningCount = (currentlyProvisioning != null ? currentlyProvisioning.get() : 0);
        int activeCount = onlineCount + tempOfflineCount + currentlyProvisioningCount;
        
        LOGGER.log(Level.FINE, "Slave count for template {0}: {1} online, {2} temp-offline, {3} offline, {4} provisioning = {5} active ({6} total nodes)", 
                  new Object[]{templateName, onlineCount, tempOfflineCount, offlineCount, currentlyProvisioningCount, activeCount, totalMatchingNodes});
        
        // Only count online, temporarily offline, and currently provisioning VMs
        return activeCount;
    }

    private String getTemplateNameFromSlave(VirtualMachineSlave slave) {
        String slaveName = slave.getNodeName();
        int dashIndex = slaveName.lastIndexOf('-');
        return dashIndex > 0 ? slaveName.substring(0, dashIndex) : slaveName;
    }

    private Object readResolve() {
        // Initialize transient fields after deserialization
        if (currentlyProvisioning == null) {
            currentlyProvisioning = new AtomicInteger(0);
        }
        if (labelSet == null) {
            // labelSet will be initialized lazily in getLabelAtoms()
        }
        return this;
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
    public boolean getLinkedClone() { return linkedClone; }
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