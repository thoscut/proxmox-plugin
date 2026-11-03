package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.jenkinsci.plugins.proxmox.pve2api.ConnectorPool;
import org.jenkinsci.plugins.proxmox.pve2api.ProxmoxAuth;
import org.jenkinsci.plugins.proxmox.pve2api.ProxmoxException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Represents a Proxmox datacenter.
 *
 * <p>Authentication is either done with a Jenkins credential ({@link #getCredentialsId()}: a
 * username/password credential or a secret text holding a Proxmox API token) or, for
 * configurations created before credentials were supported, with the username, realm and
 * password stored directly in the cloud.
 */
public class Datacenter extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(Datacenter.class.getName());

    private final String hostname;
    private final String username;
    private final String realm;
    private final Secret password;
    private final String credentialsId;
    private final Boolean ignoreSSL;

    @DataBoundConstructor
    public Datacenter(
            String hostname, String username, String realm, Secret password, String credentialsId, Boolean ignoreSSL) {
        // Jenkins addresses clouds by name, so every datacenter needs a distinct one.
        super(cloudName(hostname, authLabel(username, realm, credentialsId)));
        this.hostname = hostname;
        this.username = username;
        this.realm = realm;
        this.password = password;
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        this.ignoreSSL = ignoreSSL;
    }

    /** @deprecated use {@link #Datacenter(String, String, String, Secret, String, Boolean)} */
    @Deprecated
    public Datacenter(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        this(hostname, username, realm, password, null, ignoreSSL);
    }

    private static String authLabel(String username, String realm, String credentialsId) {
        return Util.fixEmptyAndTrim(credentialsId) != null ? credentialsId.trim() : username + "-" + realm;
    }

    static String cloudName(String hostname, String authLabel) {
        String raw = "proxmox-" + hostname + "-" + authLabel;
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    static String cloudName(String username, String realm, String hostname) {
        return cloudName(hostname, authLabel(username, realm, null));
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

    public String getCredentialsId() {
        return credentialsId;
    }

    public Boolean getIgnoreSSL() {
        return ignoreSSL;
    }

    /**
     * Identifies this datacenter in agent configurations, so it must stay stable: {@code user@realm - hostname}.
     */
    public String getDatacenterDescription() {
        return userAtRealm() + " - " + hostname;
    }

    private String userAtRealm() {
        if (credentialsId == null) {
            return username + "@" + realm;
        }
        try {
            return resolveAuth().getUserAtRealm();
        } catch (IllegalStateException e) {
            // Credential missing (or no Jenkins, in unit tests): fall back to a stable placeholder.
            return credentialsId;
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * @throws IllegalStateException if the configured credential does not exist or has an unsupported type
     */
    ProxmoxAuth resolveAuth() {
        return resolveAuth(username, realm, password, credentialsId);
    }

    static ProxmoxAuth resolveAuth(String username, String realm, Secret password, String credentialsId) {
        String id = Util.fixEmptyAndTrim(credentialsId);
        if (id == null) {
            return ProxmoxAuth.usernamePassword(username, realm, password);
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            throw new IllegalStateException("Jenkins is not running, cannot look up credential '" + id + "'");
        }
        StandardCredentials credentials = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardCredentials.class, jenkins, ACL.SYSTEM2, Collections.emptyList()),
                CredentialsMatchers.withId(id));
        if (credentials == null) {
            throw new IllegalStateException("Proxmox credential '" + id + "' not found");
        }
        if (credentials instanceof StandardUsernamePasswordCredentials) {
            StandardUsernamePasswordCredentials c = (StandardUsernamePasswordCredentials) credentials;
            return ProxmoxAuth.usernamePassword(c.getUsername(), realm, c.getPassword());
        }
        if (credentials instanceof StringCredentials) {
            return ProxmoxAuth.apiToken(((StringCredentials) credentials).getSecret());
        }
        throw new IllegalStateException("Proxmox credential '" + id + "' must be a username/password or a secret text");
    }

    ConnectorPool.Key connectorKey() {
        return new ConnectorPool.Key(hostname, resolveAuth(), ignoreSSL);
    }

    /**
     * @return the shared connector for this datacenter's configuration
     * @throws IllegalStateException if the configured credential cannot be resolved
     */
    public Connector proxmoxInstance() {
        Set<ConnectorPool.Key> inUse = new HashSet<>();
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof Datacenter) {
                    try {
                        inUse.add(((Datacenter) cloud).connectorKey());
                    } catch (IllegalStateException e) {
                        // A datacenter with a broken credential has no connector to keep.
                    }
                }
            }
        }
        return ConnectorPool.acquire(connectorKey(), inUse);
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

        @POST
        public FormValidation doCheckUsername(@QueryParameter String value, @QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(credentialsId) != null) {
                return FormValidation.ok();
            }
            return emptyStringValidation("Username", value);
        }

        public FormValidation doCheckRealm(@QueryParameter String value) {
            return emptyStringValidation("Realm", value);
        }

        @POST
        public FormValidation doCheckPassword(@QueryParameter Secret value, @QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(credentialsId) != null) {
                return FormValidation.ok();
            }
            return emptyStringValidation("Password", value == null ? null : value.getPlainText());
        }

        @POST
        public FormValidation doCheckCredentialsId(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(value) == null) {
                return FormValidation.warning(
                        "No credential selected; the username and password fields are used instead.");
            }
            try {
                resolveAuth(null, null, null, value);
                return FormValidation.ok();
            } catch (IllegalStateException e) {
                return FormValidation.error(e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
            Jenkins jenkins = Jenkins.get();
            StandardListBoxModel result = new StandardListBoxModel();
            if (!jenkins.hasPermission(Jenkins.ADMINISTER)) {
                return result.includeCurrentValue(credentialsId);
            }
            return result.includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            jenkins,
                            StandardCredentials.class,
                            Collections.emptyList(),
                            CredentialsMatchers.anyOf(
                                    CredentialsMatchers.instanceOf(StandardUsernamePasswordCredentials.class),
                                    CredentialsMatchers.instanceOf(StringCredentials.class)))
                    .includeCurrentValue(credentialsId);
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String hostname,
                @QueryParameter String username,
                @QueryParameter String realm,
                @QueryParameter Secret password,
                @QueryParameter String credentialsId,
                @QueryParameter Boolean ignoreSSL) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(hostname) == null) {
                return fieldNotSpecifiedError("Hostname");
            }
            boolean useCredential = Util.fixEmptyAndTrim(credentialsId) != null;
            if (!useCredential) {
                if (Util.fixEmptyAndTrim(username) == null) {
                    return fieldNotSpecifiedError("Username");
                }
                if (Util.fixEmptyAndTrim(realm) == null) {
                    return fieldNotSpecifiedError("Realm");
                }
                if (password == null || password.getPlainText().isEmpty()) {
                    return fieldNotSpecifiedError("Password");
                }
            }

            ProxmoxAuth auth;
            try {
                auth = resolveAuth(
                        Util.fixNull(username).trim(), Util.fixNull(realm).trim(), password, credentialsId);
            } catch (IllegalStateException e) {
                return FormValidation.error(e.getMessage());
            }

            try (Connector pveConnector = new Connector(hostname.trim(), auth, ignoreSSL)) {
                pveConnector.login();
                return FormValidation.ok("Login successful as " + auth.getUserAtRealm());
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
