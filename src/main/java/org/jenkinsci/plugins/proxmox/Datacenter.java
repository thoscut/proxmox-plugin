package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.Secret;
import hudson.util.ListBoxModel;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.security.ACL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Represents a Proxmox datacenter.
 */
public class Datacenter extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(Datacenter.class.getName());

    private final String hostname;
    private final String credentialsId;
    private final String realm;
    private final Boolean ignoreSSL;
    private final List<ProxmoxCloudSlaveTemplate> templates;
    private final int instanceCap;
    private transient Connector pveConnector;
    private transient ProxmoxCloudStatistics statistics;

    @DataBoundConstructor
    public Datacenter(String hostname, String credentialsId, String realm, Boolean ignoreSSL,
                     List<ProxmoxCloudSlaveTemplate> templates, Integer instanceCap) {
        super(hostname != null && !hostname.isEmpty() ? "Proxmox-" + hostname : "Proxmox-Datacenter");
        this.hostname = hostname;
        this.credentialsId = credentialsId;
        this.realm = realm;
        this.ignoreSSL = ignoreSSL;
        this.templates = templates != null ? templates : new ArrayList<>();
        this.instanceCap = instanceCap != null ? instanceCap : 0;
        this.pveConnector = null;
        this.statistics = null;
    }

    // Legacy constructor for backward compatibility - will be deprecated
    @Deprecated
    public Datacenter(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        this(hostname, null, realm, ignoreSSL, null, 0);
        // For legacy instances, we'll need to handle credentials differently
        // This will be handled by the credential resolution method
    }

    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        LOGGER.log(Level.INFO, "Provision called for datacenter {0} with label {1} and excessWorkload {2}",
                  new Object[]{getDatacenterDescription(), label, excessWorkload});

        List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();

        // Check if credentials are configured
        if (credentialsId == null || credentialsId.trim().isEmpty()) {
            LOGGER.log(Level.SEVERE, "Provision: No credentials configured for datacenter {0}. Please configure credentials in cloud settings.",
                      getDatacenterDescription());
            getStatistics().recordProvisioningFailure("No credentials configured");
            return plannedNodes;
        }

        if (templates == null || templates.isEmpty()) {
            LOGGER.log(Level.WARNING, "Provision: No templates configured for datacenter {0}",
                      getDatacenterDescription());
            return plannedNodes;
        }

        // Update statistics
        getStatistics().recordProvisioningAttempt();

        // Clean up any orphaned nodes to free up capacity
        try {
            cleanupOrphanedNodes();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to cleanup orphaned nodes during provisioning", e);
        }
        
        // Find templates that can provision for this label
        for (ProxmoxCloudSlaveTemplate template : templates) {
            if (template.canProvision(label)) {
                int currentSlaves = getCurrentSlaveCount();
                int availableCapacity = Math.max(0, instanceCap - currentSlaves);
                int toProvision = Math.min(excessWorkload, availableCapacity);
                
                if (toProvision > 0) {
                    for (int i = 0; i < toProvision; i++) {
                        String plannedNodeName = template.getTemplateName() + "-" + System.currentTimeMillis();
                        plannedNodes.add(new NodeProvisioner.PlannedNode(
                            plannedNodeName,
                            Computer.threadPoolForRemoting.submit(new ProvisioningCallback(template, plannedNodeName)),
                            Integer.parseInt(template.getNumExecutors())
                        ));
                    }
                    excessWorkload -= toProvision;
                    if (excessWorkload <= 0) break;
                }
            }
        }
        
        return plannedNodes;
    }

    public boolean canProvision(Label label) {
        LOGGER.log(Level.FINE, "canProvision called for datacenter {0} with label {1}",
                  new Object[]{getDatacenterDescription(), label});

        // Check if credentials are configured
        if (credentialsId == null || credentialsId.trim().isEmpty()) {
            LOGGER.log(Level.FINE, "canProvision: No credentials configured for datacenter {0}",
                      getDatacenterDescription());
            return false;
        }

        if (templates == null || templates.isEmpty()) {
            LOGGER.log(Level.WARNING, "canProvision: No templates configured for datacenter {0}",
                      getDatacenterDescription());
            return false;
        }

        // Clean up any orphaned nodes first to get accurate capacity count
        try {
            cleanupOrphanedNodes();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to cleanup orphaned nodes during canProvision check", e);
        }

        int currentSlaves = getCurrentSlaveCount();
        LOGGER.log(Level.FINE, "canProvision: Current slaves: {0}, Instance cap: {1}",
                  new Object[]{currentSlaves, instanceCap});

        for (ProxmoxCloudSlaveTemplate template : templates) {
            boolean templateCanProvision = template.canProvision(label);
            LOGGER.log(Level.FINE, "canProvision: Template {0} can provision for label {1}: {2}",
                      new Object[]{template.getTemplateName(), label, templateCanProvision});

            if (templateCanProvision) {
                boolean hasCapacity = currentSlaves < instanceCap;
                LOGGER.log(Level.FINE, "canProvision: Capacity check - current: {0} < cap: {1} = {2}",
                          new Object[]{currentSlaves, instanceCap, hasCapacity});
                return hasCapacity;
            }
        }

        LOGGER.log(Level.FINE, "canProvision: No templates can provision for label {0}", label);
        return false;
    }

    private class ProvisioningCallback implements Callable<Node> {
        private final ProxmoxCloudSlaveTemplate template;
        private final String plannedNodeName;
        private final long startTime;

        ProvisioningCallback(ProxmoxCloudSlaveTemplate template, String plannedNodeName) {
            this.template = template;
            this.plannedNodeName = plannedNodeName;
            this.startTime = System.currentTimeMillis();
        }

        public Node call() throws Exception {
            try {
                // Create and provision the node
                Node result = template.provision(Datacenter.this, plannedNodeName);

                // Add the node to Jenkins so we can monitor its connection status
                if (result != null) {
                    Jenkins.get().addNode(result);
                    LOGGER.log(Level.INFO, "Node " + plannedNodeName + " added to Jenkins, waiting for connection...");

                    // Wait for the agent to connect before reporting provisioning as complete
                    Computer computer = result.toComputer();
                    if (computer != null) {
                        // Wait up to 5 minutes for the agent to connect
                        int maxWaitSeconds = 300;
                        int waitedSeconds = 0;
                        boolean launcherInvoked = false;

                        while (waitedSeconds < maxWaitSeconds) {
                            boolean isOnline = computer.isOnline();
                            boolean isConnecting = computer.isConnecting();

                            // Check if launcher has been invoked (either connecting or was already connected)
                            if (!launcherInvoked && (isConnecting || isOnline)) {
                                launcherInvoked = true;
                                LOGGER.log(Level.INFO, "Agent " + plannedNodeName + " launcher has been invoked, connection in progress...");
                            }

                            // Log status every 10 seconds
                            if (waitedSeconds % 10 == 0 || isOnline) {
                                String status = isOnline ? "ONLINE" : (isConnecting ? "CONNECTING" : "OFFLINE");
                                LOGGER.log(Level.INFO, "Agent " + plannedNodeName + " connection status after " +
                                    waitedSeconds + " seconds: " + status);
                            }

                            if (isOnline) {
                                LOGGER.log(Level.INFO, "Agent " + plannedNodeName + " connected successfully after " +
                                    waitedSeconds + " seconds");
                                break;
                            }

                            // Check if connection failed
                            if (computer.getOfflineCause() != null &&
                                !(computer.getOfflineCause() instanceof hudson.slaves.OfflineCause.SimpleOfflineCause)) {
                                LOGGER.log(Level.WARNING, "Agent " + plannedNodeName + " went offline during connection: " +
                                    computer.getOfflineCause());
                                break;
                            }

                            Thread.sleep(1000);
                            waitedSeconds++;
                        }

                        if (!computer.isOnline()) {
                            LOGGER.log(Level.WARNING, "Agent " + plannedNodeName + " did not connect within " +
                                maxWaitSeconds + " seconds. Launcher invoked: " + launcherInvoked);
                        }
                    } else {
                        LOGGER.log(Level.WARNING, "Unable to get computer for node " + plannedNodeName);
                    }
                }

                long duration = System.currentTimeMillis() - startTime;
                getStatistics().recordProvisioningSuccess(duration);
                return result;
            } catch (Exception e) {
                getStatistics().recordProvisioningFailure(e.getMessage());
                throw e;
            }
        }
    }

    private int getCurrentSlaveCount() {
        int count = 0;
        for (hudson.model.Node node : Jenkins.get().getNodes()) {
            if (node instanceof VirtualMachineSlave) {
                VirtualMachineSlave vmSlave = (VirtualMachineSlave) node;
                if (getDatacenterDescription().equals(vmSlave.getDatacenterDescription())) {
                    count++;
                }
            }
        }
        return count;
    }

    public String getHostname() {
        return hostname;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getRealm() {
        return realm;
    }

    /**
     * Resolve credentials from Jenkins credential store.
     * @return StandardUsernamePasswordCredentials or null if not found
     */
    private StandardUsernamePasswordCredentials getCredentials() {
        if (credentialsId == null || credentialsId.isEmpty()) {
            return null;
        }

        List<StandardUsernamePasswordCredentials> credentials = CredentialsProvider.lookupCredentials(
            StandardUsernamePasswordCredentials.class,
            Jenkins.get(),
            ACL.SYSTEM,
            Collections.<DomainRequirement>emptyList()
        );

        for (StandardUsernamePasswordCredentials cred : credentials) {
            if (credentialsId.equals(cred.getId())) {
                return cred;
            }
        }
        return null;
    }

    /**
     * Get username from Jenkins credentials.
     * @return username or null if credentials not found
     */
    public String getUsername() {
        StandardUsernamePasswordCredentials creds = getCredentials();
        return creds != null ? creds.getUsername() : null;
    }

    /**
     * Get password from Jenkins credentials.
     * @return password secret or null if credentials not found
     */
    public Secret getPassword() {
        StandardUsernamePasswordCredentials creds = getCredentials();
        return creds != null ? creds.getPassword() : null;
    }

    public Boolean getIgnoreSSL() {
        return ignoreSSL;
    }

    public List<ProxmoxCloudSlaveTemplate> getTemplates() {
        return templates != null ? templates : new ArrayList<>();
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    public String getDatacenterDescription() {
        String username = getUsername();
        if (username != null) {
            return username + "@" + realm + " - " + hostname;
        } else if (credentialsId != null && !credentialsId.trim().isEmpty()) {
            return "[" + credentialsId + "]@" + realm + " - " + hostname;
        } else {
            return "[no-credentials]@" + realm + " - " + hostname;
        }
    }
    
    public ProxmoxCloudStatistics getStatistics() {
        if (statistics == null) {
            statistics = ProxmoxCloudStatistics.getInstance(this);
        }
        return statistics;
    }
    
    public void updateStatistics() {
        getStatistics().updateCurrentStatus();
    }
    
    public String getHealthSummary() {
        return getStatistics().getHealthSummary();
    }
    
    public String getDetailedStatisticsReport() {
        return getStatistics().getDetailedReport();
    }
    
    @Override
    public String toString() {
        return getDatacenterDescription();
    }
    
    public String getStatusSummary() {
        updateStatistics();
        ProxmoxCloudStatistics stats = getStatistics();
        
        StringBuilder summary = new StringBuilder();
        summary.append("<div style='font-family: monospace; font-size: 12px;'>");
        summary.append("<strong>").append(getDatacenterDescription()).append("</strong><br>");
        
        // Connection Status
        if (stats.isDatacenterReachable()) {
            summary.append("🟢 <span style='color: green;'>Connected</span>");
        } else {
            summary.append("🔴 <span style='color: red;'>Disconnected - ").append(stats.getLastErrorMessage()).append("</span>");
        }
        
        summary.append("<br><br>");
        
        // Capacity Information
        summary.append("<strong>Capacity:</strong> ")
               .append(stats.getCurrentSlaveCount()).append("/").append(instanceCap)
               .append(" slaves (").append(Math.max(0, instanceCap - stats.getCurrentSlaveCount())).append(" available)<br>");
        
        // Slave Status
        summary.append("<strong>Slaves:</strong> ")
               .append(stats.getOnlineSlaves()).append(" online, ")
               .append(stats.getOfflineSlaves()).append(" offline, ")
               .append(stats.getTemporarilyOfflineSlaves()).append(" temp-offline");
        
        if (stats.getProvisioningSlaves() > 0) {
            summary.append(", ").append(stats.getProvisioningSlaves()).append(" provisioning");
        }
        summary.append("<br>");
        
        // Provisioning Statistics
        if (stats.getTotalProvisioningAttempts() > 0) {
            summary.append("<strong>Provisioning:</strong> ")
                   .append(String.format("%.1f%% success rate ", stats.getSuccessRate()))
                   .append("(").append(stats.getSuccessfulProvisionings()).append("/")
                   .append(stats.getTotalProvisioningAttempts()).append(" attempts)<br>");
        }
        
        // Node Health Summary
        Map<String, ProxmoxCloudStatistics.NodeHealth> nodeHealth = stats.getNodeHealthMap();
        if (!nodeHealth.isEmpty()) {
            summary.append("<strong>Nodes:</strong> ");
            int onlineNodes = 0;
            int totalNodes = nodeHealth.size();
            
            for (ProxmoxCloudStatistics.NodeHealth health : nodeHealth.values()) {
                if (health.online) onlineNodes++;
            }
            
            summary.append(onlineNodes).append("/").append(totalNodes).append(" online");
            
            // Show individual node status
            summary.append("<br>");
            for (ProxmoxCloudStatistics.NodeHealth health : nodeHealth.values()) {
                summary.append("&nbsp;&nbsp;• ").append(health.nodeName).append(": ");
                if (health.online) {
                    summary.append(String.format("🟢 Online (CPU: %.0f%%, RAM: %.0f%%, VMs: %d)", 
                                  health.cpuUsage, health.memoryUsage, health.runningVMs));
                } else {
                    summary.append("🔴 ").append(health.status);
                }
                summary.append("<br>");
            }
        }
        
        summary.append("</div>");
        return summary.toString();
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public Connector proxmoxInstance() {
        if (pveConnector == null) {
            if (credentialsId == null || credentialsId.trim().isEmpty()) {
                throw new IllegalStateException("No credentials configured for Proxmox datacenter '" + hostname + "'. Please configure credentials in the cloud settings.");
            }

            String username = getUsername();
            Secret password = getPassword();

            if (username == null || password == null) {
                throw new IllegalStateException("Unable to resolve credentials with ID '" + credentialsId + "' for datacenter '" + hostname + "'. Please verify the credentials exist and are accessible.");
            }

            pveConnector = new Connector(hostname, username, realm, password, ignoreSSL);
        }
        return pveConnector;
    }

    public List<String> getNodes() {
        Connector pveConnector = proxmoxInstance();
        try {
            return pveConnector.getNodes();
        } catch (LoginException e) {
            return new ArrayList<String>();
        }
    }

    public HashMap<String, Integer> getQemuMachines(String node) {
        if (node == null || node.isEmpty()) {
            return new HashMap<String, Integer>();
        }

        Connector pveConnector = proxmoxInstance();
        try {
            return pveConnector.getQemuMachines(node);
        } catch (LoginException e) {
            return new HashMap<String, Integer>();
        }
    }

    public List<String> getQemuMachineSnapshots(String node, Integer vmid) {
        if (node == null || node.isEmpty() || vmid < 1) {
            return new ArrayList<String>();
        }

        Connector pveConnector = proxmoxInstance();
        try {
            return pveConnector.getQemuMachineSnapshots(node, vmid);
        } catch (LoginException e) {
            return new ArrayList<String>();
        }
    }

    public boolean canTerminate(Computer computer) {
        // Check if this computer is managed by this cloud instance
        if (!(computer instanceof VirtualMachineSlaveComputer)) {
            return false;
        }

        VirtualMachineSlaveComputer vmComputer = (VirtualMachineSlaveComputer) computer;
        Node node = vmComputer.getNode();

        if (!(node instanceof VirtualMachineSlave)) {
            return false;
        }

        VirtualMachineSlave vmSlave = (VirtualMachineSlave) node;

        // Check if this slave belongs to this datacenter
        if (!getDatacenterDescription().equals(vmSlave.getDatacenterDescription())) {
            return false;
        }

        LOGGER.log(Level.FINE, "Can terminate VM slave: {0} (VM ID: {1})",
                  new Object[]{vmSlave.getNodeName(), vmSlave.getVirtualMachineId().toString()});
        return true;
    }

    public void terminate(Computer computer) {
        if (!canTerminate(computer)) {
            LOGGER.log(Level.WARNING, "Cannot terminate computer: {0} - not managed by this cloud instance",
                      computer.getName());
            return;
        }

        VirtualMachineSlaveComputer vmComputer = (VirtualMachineSlaveComputer) computer;
        VirtualMachineSlave vmSlave = (VirtualMachineSlave) vmComputer.getNode();

        LOGGER.log(Level.INFO, "Terminating VM slave: {0} (VM ID: {1}) on node: {2}",
                  new Object[]{vmSlave.getNodeName(), vmSlave.getVirtualMachineId().toString(), vmSlave.getDatacenterNode()});

        try {
            // Update statistics
            getStatistics().recordTermination();

            Connector proxmoxApi = proxmoxInstance();
            Integer vmId = vmSlave.getVirtualMachineId();
            String nodeName = vmSlave.getDatacenterNode();

            // Stop the VM if it's running
            boolean wasRunning = proxmoxApi.isQemuMachineRunning(nodeName, vmId);
            if (wasRunning) {
                LOGGER.log(Level.INFO, "Stopping VM {0} (ID: {1}) before deletion",
                          new Object[]{vmSlave.getNodeName(), vmId.toString()});
                String stopTask = proxmoxApi.stopQemuMachine(nodeName, vmId);

                // Wait for stop task to complete and check result
                try {
                    JSONObject stopTaskResult = proxmoxApi.waitForTaskToFinish(nodeName, stopTask);
                    String stopStatus = stopTaskResult.getString("status");
                    String stopExitStatus = stopTaskResult.has("exitstatus") && stopTaskResult.get("exitstatus") != null ?
                                          stopTaskResult.getString("exitstatus") : null;

                    // Proxmox tasks can have status "stopped" with exitstatus "OK" for success
                    // or status "OK" for immediate success
                    boolean isSuccess = "OK".equals(stopStatus) ||
                                       ("stopped".equals(stopStatus) && "OK".equals(stopExitStatus));

                    if (isSuccess) {
                        LOGGER.log(Level.INFO, "VM {0} (ID: {1}) stopped successfully - status: {2}, exitstatus: {3}",
                                  new Object[]{vmSlave.getNodeName(), vmId.toString(), stopStatus, stopExitStatus});
                    } else {
                        String errorMsg = "VM stop task failed with status: " + stopStatus;
                        if (stopExitStatus != null) {
                            errorMsg += ", exit status: " + stopExitStatus;
                        }
                        LOGGER.log(Level.WARNING, "VM stop failed for {0}: {1}",
                                  new Object[]{vmSlave.getNodeName(), errorMsg});
                        // Continue with deletion even if stop failed
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "VM stop task failed for " + vmSlave.getNodeName() +
                              ", but continuing with deletion: " + e.getMessage(), e);
                }
            }

            // Delete the VM
            LOGGER.log(Level.INFO, "Deleting VM {0} (ID: {1})",
                      new Object[]{vmSlave.getNodeName(), vmId.toString()});

            // Delete the VM using the Proxmox API
            String deleteResult = proxmoxApi.deleteQemuMachine(nodeName, vmId);

            // Remove the node from Jenkins
            Jenkins jenkins = Jenkins.get();
            jenkins.removeNode(vmSlave);

            LOGGER.log(Level.INFO, "Successfully terminated and cleaned up VM slave: {0} (ID: {1})",
                      new Object[]{vmSlave.getNodeName(), vmId.toString()});

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to terminate VM slave: " + vmSlave.getNodeName() +
                      " (ID: " + vmSlave.getVirtualMachineId().toString() + ")", e);

            // Even if VM deletion fails, try to remove from Jenkins to prevent orphaned nodes
            try {
                Jenkins.get().removeNode(vmSlave);
                LOGGER.log(Level.INFO, "Removed orphaned Jenkins node: {0} after VM deletion failure",
                          vmSlave.getNodeName());
            } catch (Exception removeException) {
                LOGGER.log(Level.SEVERE, "Failed to remove Jenkins node after VM deletion failure", removeException);
            }
        }
    }

    /**
     * Clean up orphaned or stale Proxmox nodes that no longer correspond to actual VMs.
     * This method checks all VirtualMachineSlave nodes belonging to this datacenter
     * and removes any that cannot be reached or are no longer valid.
     */
    public void cleanupOrphanedNodes() {
        LOGGER.log(Level.INFO, "Starting cleanup of orphaned nodes for datacenter: {0}", getDatacenterDescription());

        List<VirtualMachineSlave> nodesToRemove = new ArrayList<>();

        // Find all VirtualMachineSlave nodes belonging to this datacenter
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof VirtualMachineSlave) {
                VirtualMachineSlave vmSlave = (VirtualMachineSlave) node;
                if (getDatacenterDescription().equals(vmSlave.getDatacenterDescription())) {

                    Computer computer = vmSlave.toComputer();
                    if (computer != null) {
                        // Check if node is permanently offline or unreachable
                        if (shouldCleanupNode(vmSlave, computer)) {
                            nodesToRemove.add(vmSlave);
                        }
                    } else {
                        // Computer is null, definitely orphaned
                        LOGGER.log(Level.WARNING, "Found orphaned node with null computer: {0}", vmSlave.getNodeName());
                        nodesToRemove.add(vmSlave);
                    }
                }
            }
        }

        // Remove identified orphaned nodes
        for (VirtualMachineSlave nodeToRemove : nodesToRemove) {
            try {
                LOGGER.log(Level.INFO, "Removing orphaned node: {0} (VM ID: {1})",
                          new Object[]{nodeToRemove.getNodeName(), nodeToRemove.getVirtualMachineId().toString()});

                Computer computer = nodeToRemove.toComputer();
                if (computer != null) {
                    computer.disconnect(new ProxmoxOfflineCause("Node cleanup - VM no longer accessible"));
                }

                Jenkins.get().removeNode(nodeToRemove);
                getStatistics().recordTermination();

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to remove orphaned node: " + nodeToRemove.getNodeName(), e);
            }
        }

        if (nodesToRemove.isEmpty()) {
            LOGGER.log(Level.FINE, "No orphaned nodes found for datacenter: {0}", getDatacenterDescription());
        } else {
            LOGGER.log(Level.INFO, "Cleaned up {0} orphaned nodes for datacenter: {1}",
                      new Object[]{nodesToRemove.size(), getDatacenterDescription()});
        }
    }

    /**
     * Determine if a node should be cleaned up based on its state and VM availability.
     */
    private boolean shouldCleanupNode(VirtualMachineSlave vmSlave, Computer computer) {
        try {
            // Check if computer is manually set to offline permanently
            if (computer.isManualLaunchAllowed() && computer.isOffline() &&
                computer.getOfflineCause() instanceof hudson.slaves.OfflineCause.UserCause) {
                LOGGER.log(Level.FINE, "Skipping manually offline node: {0}", vmSlave.getNodeName());
                return false;
            }

            // Check if the VM still exists in Proxmox
            Connector proxmoxApi = proxmoxInstance();
            String nodeName = vmSlave.getDatacenterNode();
            Integer vmId = vmSlave.getVirtualMachineId();

            if (nodeName == null || vmId == null) {
                LOGGER.log(Level.WARNING, "Node {0} has invalid datacenter node or VM ID", vmSlave.getNodeName());
                return true; // Cleanup invalid nodes
            }

            // Try to get VM status from Proxmox
            try {
                // Just check if we can query the VM status - if this succeeds, VM exists
                proxmoxApi.getQemuMachineStatus(nodeName, vmId);
                return false; // VM exists, don't cleanup

            } catch (Exception e) {
                // If we can't query the VM, it likely doesn't exist
                LOGGER.log(Level.INFO, "VM {0} (ID: {1}) on node {2} appears to be missing from Proxmox: {3}",
                          new Object[]{vmSlave.getNodeName(), vmId.toString(), nodeName, e.getMessage()});
                return true; // VM doesn't exist, cleanup the node
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error checking node " + vmSlave.getNodeName() + " for cleanup: " + e.getMessage(), e);
            return false; // When in doubt, don't cleanup
        }
    }

    /**
     * Simple offline cause for cleanup operations.
     */
    private static class ProxmoxOfflineCause extends hudson.slaves.OfflineCause {
        private final String reason;

        ProxmoxOfflineCause(String reason) {
            this.reason = reason;
        }

        @Override
        public String toString() {
            return reason;
        }
    }

    @Extension
    @Symbol("datacenter")
    public static final class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Proxmox Datacenter";
        }

        @Override
        public boolean configure(StaplerRequest2 req, net.sf.json.JSONObject o) throws FormException {
            save();
            return super.configure(req, o);
        }

        private FormValidation fieldNotSpecifiedError(String fieldName) {
            return FormValidation.error(fieldName + " not specified");
        }

        private FormValidation emptyStringValidation(String fieldName, String value) {
            if (Util.fixEmptyAndTrim(value) == null) return fieldNotSpecifiedError(fieldName);
            else return FormValidation.ok();
        }

        public FormValidation doCheckHostname(@QueryParameter String value) {
            return emptyStringValidation("Hostname", value);
        }

        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.warning("No credentials selected. Please select credentials to authenticate with Proxmox.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckRealm(@QueryParameter String value) {
            return emptyStringValidation("Realm", value);
        }

        /**
         * Fills the credentials dropdown with available username/password credentials.
         */
        public ListBoxModel doFillCredentialsIdItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return new StandardListBoxModel()
                .includeEmptyValue()
                .includeAs(ACL.SYSTEM, Jenkins.get(), StandardUsernamePasswordCredentials.class);
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String hostname,
                @QueryParameter String credentialsId,
                @QueryParameter String realm,
                @QueryParameter Boolean ignoreSSL) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            try {
                if (hostname.isEmpty()) {
                    return fieldNotSpecifiedError("Hostname");
                }
                if (Util.fixEmptyAndTrim(credentialsId) == null) {
                    return fieldNotSpecifiedError("Credentials");
                }

                // Resolve credentials
                List<StandardUsernamePasswordCredentials> credentials = CredentialsProvider.lookupCredentials(
                    StandardUsernamePasswordCredentials.class,
                    Jenkins.get(),
                    ACL.SYSTEM,
                    Collections.<DomainRequirement>emptyList()
                );

                StandardUsernamePasswordCredentials creds = null;
                for (StandardUsernamePasswordCredentials cred : credentials) {
                    if (credentialsId.equals(cred.getId())) {
                        creds = cred;
                        break;
                    }
                }

                if (creds == null) {
                    return FormValidation.error("Selected credentials not found. Please select valid credentials.");
                }

                String username = creds.getUsername();
                Secret password = creds.getPassword();
                if (realm.isEmpty()) {
                    return fieldNotSpecifiedError("Realm");
                }
                if (password.getPlainText().isEmpty()) {
                    return fieldNotSpecifiedError("Password");
                }

                Connector pveConnector = new Connector(hostname, username, realm, password, ignoreSSL);
                pveConnector.login();
                return FormValidation.ok("Login successful");

            } catch (LoginException e) {
                LOGGER.log(Level.SEVERE, "Authentication error", e);
                return FormValidation.error(
                        "Authentication error. Please verify your login credentials or check logs.");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Connection error", e);
                
                // Check for SSL certificate errors in the exception chain
                Throwable cause = e;
                while (cause != null) {
                    if (cause instanceof javax.net.ssl.SSLHandshakeException) {
                        return FormValidation.error(
                                "SSL certificate validation failed. Either add the certificate to your truststore or enable 'Ignore SSL certificates' option.");
                    }
                    cause = cause.getCause();
                }
                
                return FormValidation.error(
                        "Connection error: " + e.getMessage() + ". Please verify your hostname, SSL settings, or check logs.");
            }
        }
        
        public FormValidation doCheckCloudHealth(@QueryParameter String datacenterDescription) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            
            if (datacenterDescription == null || datacenterDescription.trim().isEmpty()) {
                return FormValidation.warning("No datacenter selected");
            }
            
            // Find the datacenter instance
            Datacenter datacenter = null;
            for (hudson.slaves.Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof Datacenter) {
                    Datacenter dc = (Datacenter) cloud;
                    if (datacenterDescription.equals(dc.getDatacenterDescription())) {
                        datacenter = dc;
                        break;
                    }
                }
            }
            
            if (datacenter == null) {
                return FormValidation.error("Datacenter not found: " + datacenterDescription);
            }
            
            try {
                datacenter.updateStatistics();
                String healthSummary = datacenter.getHealthSummary();
                
                if (healthSummary.contains("❌")) {
                    return FormValidation.error("Health Check Failed: " + healthSummary);
                } else if (healthSummary.contains("⚠️")) {
                    return FormValidation.warning("Health Check Warning: " + healthSummary);
                } else {
                    return FormValidation.ok("Health Check Passed: " + healthSummary);
                }
                
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Health check failed for " + datacenterDescription, e);
                return FormValidation.error("Health check failed: " + e.getMessage());
            }
        }
        
        public FormValidation doGetDetailedStatistics(@QueryParameter String datacenterDescription) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            
            if (datacenterDescription == null || datacenterDescription.trim().isEmpty()) {
                return FormValidation.warning("No datacenter selected");
            }
            
            // Find the datacenter instance
            Datacenter datacenter = null;
            for (hudson.slaves.Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof Datacenter) {
                    Datacenter dc = (Datacenter) cloud;
                    if (datacenterDescription.equals(dc.getDatacenterDescription())) {
                        datacenter = dc;
                        break;
                    }
                }
            }
            
            if (datacenter == null) {
                return FormValidation.error("Datacenter not found: " + datacenterDescription);
            }
            
            try {
                datacenter.updateStatistics();
                String detailedReport = datacenter.getDetailedStatisticsReport();
                return FormValidation.ok(detailedReport);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to get statistics for " + datacenterDescription, e);
                return FormValidation.error("Failed to get statistics: " + e.getMessage());
            }
        }
    }
    
    /**
     * Provides access to the detailed status page.
     * This method makes the status.jelly page accessible via URL.
     */
    public Object getStatus() {
        return this;
    }
    
    /**
     * Manual cleanup action accessible from the web UI.
     */
    @POST
    public void doCleanupOrphans(StaplerRequest2 req, org.kohsuke.stapler.StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        LOGGER.log(Level.INFO, "Manual cleanup of orphaned nodes requested for: {0}", getDatacenterDescription());
        cleanupOrphanedNodes();

        rsp.sendRedirect2(Jenkins.get().getRootUrl() + "manage/cloud");
    }

    /**
     * Deletes this cloud from Jenkins configuration.
     * This method is called when the user clicks "Delete Cloud" from the status page.
     */
    @POST
    public void doDelete(StaplerRequest2 req, org.kohsuke.stapler.StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        
        LOGGER.log(Level.INFO, "Deleting Proxmox cloud: {0}", getDatacenterDescription());
        
        // Remove this cloud from Jenkins
        Jenkins jenkins = Jenkins.get();
        List<Cloud> clouds = new ArrayList<>(jenkins.clouds);
        clouds.remove(this);
        jenkins.clouds.replaceBy(clouds);
        jenkins.save();
        
        // Redirect back to cloud management page
        rsp.sendRedirect("../");
    }
}
