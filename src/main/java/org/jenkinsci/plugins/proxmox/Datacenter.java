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
import net.sf.json.JSONObject;
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
    private final String username;
    private final String realm;
    private final Secret password;
    private final Boolean ignoreSSL;
    private final List<ProxmoxCloudSlaveTemplate> templates;
    private final int instanceCap;
    private transient Connector pveConnector;
    private transient ProxmoxCloudStatistics statistics;

    @DataBoundConstructor
    public Datacenter(String hostname, String username, String realm, Secret password, Boolean ignoreSSL, 
                     List<ProxmoxCloudSlaveTemplate> templates, Integer instanceCap) {
        super(hostname != null && !hostname.isEmpty() ? "Proxmox-" + hostname : "Proxmox-Datacenter");
        this.hostname = hostname;
        this.username = username;
        this.realm = realm;
        this.password = password;
        this.ignoreSSL = ignoreSSL;
        this.templates = templates != null ? templates : new ArrayList<>();
        this.instanceCap = instanceCap != null ? instanceCap : 0;
        this.pveConnector = null;
        this.statistics = null;
    }

    // Legacy constructor for backward compatibility
    public Datacenter(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        this(hostname, username, realm, password, ignoreSSL, null, 0);
    }

    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();
        
        if (templates == null || templates.isEmpty()) {
            return plannedNodes;
        }
        
        // Update statistics
        getStatistics().recordProvisioningAttempt();
        
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
        if (templates == null || templates.isEmpty()) {
            return false;
        }
        
        for (ProxmoxCloudSlaveTemplate template : templates) {
            if (template.canProvision(label)) {
                return getCurrentSlaveCount() < instanceCap;
            }
        }
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
                Node result = template.provision(Datacenter.this, plannedNodeName);
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

    public String getUsername() {
        return username;
    }

    public String getRealm() {
        return realm;
    }

    public Secret getPassword() {
        return password;
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
        return username + "@" + realm + " - " + hostname;
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

    @Extension
    @Symbol("datacenter")
    public static final class DescriptorImpl extends Descriptor<Cloud> {
        
        public DescriptorImpl() {
            super(Datacenter.class);
        }
        
        @Override
        public String getDisplayName() {
            return "Proxmox Datacenter";
        }

        @Override
        public boolean configure(StaplerRequest2 req, JSONObject o) throws FormException {
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

        public FormValidation doCheckUsername(@QueryParameter String value) {
            return emptyStringValidation("Username", value);
        }

        public FormValidation doCheckRealm(@QueryParameter String value) {
            return emptyStringValidation("Realm", value);
        }

        public FormValidation doCheckPassword(@QueryParameter Secret value) {
            return emptyStringValidation("Password", value.getPlainText());
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String hostname,
                @QueryParameter String username,
                @QueryParameter String realm,
                @QueryParameter Secret password,
                @QueryParameter Boolean ignoreSSL) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            try {
                if (hostname.isEmpty()) {
                    return fieldNotSpecifiedError("Hostname");
                }
                if (username.isEmpty()) {
                    return fieldNotSpecifiedError("Username");
                }
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
