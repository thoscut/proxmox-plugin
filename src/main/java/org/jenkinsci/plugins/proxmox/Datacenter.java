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
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;
import org.jenkinsci.Symbol;

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
        
        // Find templates that can provision for this label
        for (ProxmoxCloudSlaveTemplate template : templates) {
            if (template.canProvision(label)) {
                int currentSlaves = getCurrentSlaveCount();
                int availableCapacity = Math.max(0, instanceCap - currentSlaves);
                int toProvision = Math.min(excessWorkload, availableCapacity);
                
                if (toProvision > 0) {
                    for (int i = 0; i < toProvision; i++) {
                        plannedNodes.add(new NodeProvisioner.PlannedNode(
                            template.getTemplateName() + "-" + System.currentTimeMillis(),
                            Computer.threadPoolForRemoting.submit(new ProvisioningCallback(template)),
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

        ProvisioningCallback(ProxmoxCloudSlaveTemplate template) {
            this.template = template;
        }

        public Node call() throws Exception {
            return template.provision(Datacenter.this);
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
    }
}
