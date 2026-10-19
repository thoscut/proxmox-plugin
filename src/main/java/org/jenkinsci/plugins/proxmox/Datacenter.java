package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.Secret;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * API connectors by datacenter description. Saving the configuration replaces the {@link Datacenter}
     * instance, so connectors are kept here to be reused or closed instead of leaking their HTTP client.
     */
    private static final Map<String, CachedConnector> CONNECTORS = new ConcurrentHashMap<>();

    private static final class CachedConnector {
        private final String settingsDigest;
        private final Connector connector;

        private CachedConnector(String settingsDigest, Connector connector) {
            this.settingsDigest = settingsDigest;
            this.connector = connector;
        }
    }

    @DataBoundConstructor
    public Datacenter(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        super("Datacenter(proxmox)");
        this.hostname = hostname;
        this.username = username;
        this.realm = realm;
        this.password = password;
        this.ignoreSSL = ignoreSSL;
    }

    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        return Collections.emptySet();
    }

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

    public Connector proxmoxInstance() {
        // Validate before replacing a cached connector, so an invalid hostname does not leave a closed one behind
        Connector.buildBaseURL(hostname);
        String settingsDigest = Util.getDigestOf(String.join(
                "\n", hostname, username, realm, Secret.toString(password), String.valueOf(ignoreSSL)));
        return CONNECTORS.compute(getDatacenterDescription(), (description, cached) -> {
                    if (cached != null) {
                        if (cached.settingsDigest.equals(settingsDigest)) {
                            return cached;
                        }
                        cached.connector.close();
                    }
                    return new CachedConnector(
                            settingsDigest, new Connector(hostname, username, realm, password, ignoreSSL));
                })
                .connector;
    }

    public List<String> getNodes() {
        try {
            return proxmoxInstance().getNodes();
        } catch (LoginException | IOException | IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Failed to list nodes of Proxmox datacenter " + getDatacenterDescription(), e);
            return new ArrayList<String>();
        }
    }

    /**
     * @return the virtual machines of the node, mapped from VM id to VM name.
     */
    public Map<Integer, String> getQemuMachines(String node) {
        if (node == null || node.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            return proxmoxInstance().getQemuMachines(node);
        } catch (LoginException | IOException | IllegalArgumentException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Failed to list virtual machines of node " + node + " in Proxmox datacenter "
                            + getDatacenterDescription(),
                    e);
            return Collections.emptyMap();
        }
    }

    public List<String> getQemuMachineSnapshots(String node, Integer vmid) {
        if (node == null || node.isEmpty() || vmid == null || vmid < 1) {
            return new ArrayList<String>();
        }

        try {
            return proxmoxInstance().getQemuMachineSnapshots(node, vmid);
        } catch (LoginException | IOException | IllegalArgumentException e) {
            LOGGER.log(
                    Level.WARNING,
                    "Failed to list snapshots of virtual machine " + vmid + " in Proxmox datacenter "
                            + getDatacenterDescription(),
                    e);
            return new ArrayList<String>();
        }
    }

    @Extension
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

        @POST
        public FormValidation doCheckPassword(@QueryParameter Secret value) {
            return emptyStringValidation("Password", Secret.toString(value));
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
                if (Util.fixEmptyAndTrim(hostname) == null) {
                    return fieldNotSpecifiedError("Hostname");
                }
                if (Util.fixEmptyAndTrim(username) == null) {
                    return fieldNotSpecifiedError("Username");
                }
                if (Util.fixEmptyAndTrim(realm) == null) {
                    return fieldNotSpecifiedError("Realm");
                }
                if (Secret.toString(password).isEmpty()) {
                    return fieldNotSpecifiedError("Password");
                }

                try (Connector pveConnector = new Connector(hostname, username, realm, password, ignoreSSL)) {
                    pveConnector.login();
                    return FormValidation.ok("Login successful");
                }

            } catch (LoginException e) {
                LOGGER.log(Level.WARNING, "Authentication error", e);
                return FormValidation.error(
                        "Authentication error. Please verify your login credentials or check logs.");
            } catch (IOException | IllegalArgumentException e) {
                LOGGER.log(Level.WARNING, "Connection test failed", e);
                return FormValidation.error("Connection failed: " + e.getMessage());
            }
        }
    }
}
