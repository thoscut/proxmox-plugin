package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.jenkinsci.plugins.proxmox.pve2api.ProxmoxException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
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
    private transient Connector pveConnector;

    @DataBoundConstructor
    public Datacenter(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        // Jenkins addresses clouds by name, so every datacenter needs a distinct one.
        super(cloudName(username, realm, hostname));
        this.hostname = hostname;
        this.username = username;
        this.realm = realm;
        this.password = password;
        this.ignoreSSL = ignoreSSL;
        this.pveConnector = null;
    }

    static String cloudName(String username, String realm, String hostname) {
        String raw = "proxmox-" + hostname + "-" + username + "-" + realm;
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        return Collections.emptySet();
    }

    @Override
    public boolean canProvision(Label label) {
        return false;
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

    public String getDatacenterDescription() {
        return username + "@" + realm + " - " + hostname;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public synchronized Connector proxmoxInstance() {
        if (pveConnector == null) {
            pveConnector = new Connector(hostname, username, realm, password, ignoreSSL);
        }
        return pveConnector;
    }

    public List<String> getNodes() {
        try {
            return proxmoxInstance().getNodes();
        } catch (LoginException | ProxmoxException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not list nodes of " + getDatacenterDescription(), e);
            return new ArrayList<>();
        }
    }

    /**
     * @return the QEMU virtual machines of a node, keyed and sorted by VM id, with their names as values.
     */
    public Map<Integer, String> getQemuMachines(String node) {
        if (node == null || node.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            return proxmoxInstance().getQemuMachines(node);
        } catch (LoginException | ProxmoxException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not list virtual machines of node " + node, e);
            return Collections.emptyMap();
        }
    }

    public List<String> getQemuMachineSnapshots(String node, Integer vmid) {
        if (node == null || node.isEmpty() || vmid == null || vmid < 1) {
            return new ArrayList<>();
        }

        try {
            return proxmoxInstance().getQemuMachineSnapshots(node, vmid);
        } catch (LoginException | ProxmoxException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Could not list snapshots of virtual machine " + vmid, e);
            return new ArrayList<>();
        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Proxmox Datacenter";
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
            return emptyStringValidation("Password", value == null ? null : value.getPlainText());
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String hostname,
                @QueryParameter String username,
                @QueryParameter String realm,
                @QueryParameter Secret password,
                @QueryParameter Boolean ignoreSSL) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(hostname) == null) {
                return fieldNotSpecifiedError("Hostname");
            }
            if (Util.fixEmptyAndTrim(username) == null) {
                return fieldNotSpecifiedError("Username");
            }
            if (Util.fixEmptyAndTrim(realm) == null) {
                return fieldNotSpecifiedError("Realm");
            }
            if (password == null || password.getPlainText().isEmpty()) {
                return fieldNotSpecifiedError("Password");
            }

            try (Connector pveConnector =
                    new Connector(hostname.trim(), username.trim(), realm.trim(), password, ignoreSSL)) {
                pveConnector.login();
                return FormValidation.ok("Login successful");
            } catch (LoginException e) {
                LOGGER.log(Level.WARNING, "Proxmox authentication error", e);
                return FormValidation.error("Authentication error: " + e.getMessage());
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Proxmox connection test failed", e);
                return FormValidation.error("Connection failed: " + e.getMessage());
            }
        }
    }
}
