package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import hudson.Extension;
import hudson.Util;
import hudson.init.Terminator;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Executor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.OfflineCause;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.jenkinsci.plugins.proxmox.pve2api.ConnectorPool;
import org.jenkinsci.plugins.proxmox.pve2api.ProxmoxAuth;
import org.jenkinsci.plugins.proxmox.pve2api.ProxmoxException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

/**
 * Represents a Proxmox datacenter.
 *
 * <p>Authentication is either done with a Jenkins credential ({@link #getCredentialsId()}: a
 * username/password credential or a secret text holding a Proxmox API token) or, for
 * configurations created before credentials were supported, with the username, realm and
 * password stored directly in the cloud.
 *
 * <p>Besides serving agents that are bound to an existing virtual machine by hand, a datacenter
 * can provision agents on demand: every {@link ProxmoxCloudSlaveTemplate} describes a virtual
 * machine template that is cloned when a build needs an agent with a matching label.
 */
public class Datacenter extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(Datacenter.class.getName());

    private final String hostname;
    private final String username;
    private final String realm;
    private final Secret password;
    private final String credentialsId;
    private final Boolean ignoreSSL;
    private List<ProxmoxCloudSlaveTemplate> templates;
    private int instanceCap;

    /** Credentials are looked up at most this often; see {@link #resolveAuth()}. */
    static final long AUTH_CACHE_MS = 60 * 1000;

    static final String DEFAULT_REALM = "pve";

    private transient volatile ProxmoxAuth resolvedAuth;
    private transient volatile long resolvedAuthAtMs;
    private transient ProxmoxCloudStatistics statistics;

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

    /** Creates a datacenter that authenticates with a Jenkins credential and provisions agents from templates. */
    public Datacenter(
            String hostname,
            String credentialsId,
            String realm,
            Boolean ignoreSSL,
            List<ProxmoxCloudSlaveTemplate> templates,
            Integer instanceCap) {
        this(hostname, null, realm, null, credentialsId, ignoreSSL);
        setTemplates(templates);
        setInstanceCap(instanceCap != null ? instanceCap : 0);
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

    // ------------------------------------------------------------------
    // Provisioning of agents from templates
    // ------------------------------------------------------------------

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Cloud.CloudState state, int excessWorkload) {
        return provision(state.getLabel(), excessWorkload);
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(Label label, int excessWorkload) {
        LOGGER.log(
                Level.FINE,
                "Provision called for datacenter {0} with label {1} and excessWorkload {2}",
                new Object[] {getDatacenterDescription(), label, excessWorkload});

        List<NodeProvisioner.PlannedNode> plannedNodes = new ArrayList<>();
        if (getTemplates().isEmpty()) {
            return plannedNodes;
        }

        if (!hasUsableCredentials()) {
            LOGGER.log(
                    Level.SEVERE,
                    "Provision: No usable credentials configured for datacenter {0}. Please configure credentials in"
                            + " the cloud settings.",
                    getDatacenterDescription());
            getStatistics().recordProvisioningFailure("No credentials configured");
            return plannedNodes;
        }

        getStatistics().recordProvisioningAttempt();

        // Orphans are cleaned up by ProxmoxCleanupTask; doing it here would delay the start of builds.

        for (ProxmoxCloudSlaveTemplate template : getTemplates()) {
            if (template.canProvision(label)) {
                int currentSlaves = getCurrentSlaveCount();
                int availableCapacity = Math.max(0, instanceCap - currentSlaves);
                int toProvision = Math.min(excessWorkload, availableCapacity);

                if (toProvision > 0) {
                    for (int i = 0; i < toProvision; i++) {
                        String plannedNodeName = template.getTemplateName() + "-" + System.currentTimeMillis();

                        // Identifies the provisioning attempt for the cloud statistics plugin
                        ProvisioningActivity.Id provisioningId = new ProvisioningActivity.Id(
                                getDatacenterDescription(), template.getTemplateName(), plannedNodeName);

                        ProxmoxPlannedNode plannedNode = new ProxmoxPlannedNode(
                                provisioningId,
                                getDatacenterDescription(),
                                template.getTemplateName(),
                                Computer.threadPoolForRemoting.submit(
                                        new ProvisioningCallback(template, plannedNodeName, provisioningId)),
                                Integer.parseInt(template.getNumExecutors()));

                        plannedNodes.add(plannedNode);
                    }
                    excessWorkload -= toProvision;
                    if (excessWorkload <= 0) break;
                }
            }
        }

        return plannedNodes;
    }

    @Override
    public boolean canProvision(Cloud.CloudState state) {
        return canProvision(state.getLabel());
    }

    @Override
    public boolean canProvision(Label label) {
        if (getTemplates().isEmpty()) {
            return false;
        }
        if (!hasUsableCredentials()) {
            LOGGER.log(
                    Level.FINE,
                    "canProvision: No usable credentials configured for datacenter {0}",
                    getDatacenterDescription());
            return false;
        }

        // Orphans are cleaned up by ProxmoxCleanupTask; doing it here would delay scheduling decisions.

        int currentSlaves = getCurrentSlaveCount();
        for (ProxmoxCloudSlaveTemplate template : getTemplates()) {
            if (template.canProvision(label)) {
                boolean hasCapacity = currentSlaves < instanceCap;
                LOGGER.log(
                        Level.FINE,
                        "canProvision: Template {0} matches label {1}; capacity {2} of {3} used",
                        new Object[] {template.getTemplateName(), label, currentSlaves, instanceCap});
                return hasCapacity;
            }
        }
        return false;
    }

    /** @return whether a login can be attempted at all: a resolvable credential or a legacy username. */
    private boolean hasUsableCredentials() {
        if (credentialsId == null) {
            return Util.fixEmptyAndTrim(username) != null;
        }
        try {
            resolveAuth();
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private class ProvisioningCallback implements Callable<Node> {
        private final ProxmoxCloudSlaveTemplate template;
        private final String plannedNodeName;
        private final long startTime;
        private final ProvisioningActivity.Id provisioningId;

        ProvisioningCallback(
                ProxmoxCloudSlaveTemplate template, String plannedNodeName, ProvisioningActivity.Id provisioningId) {
            this.template = template;
            this.plannedNodeName = plannedNodeName;
            this.startTime = System.currentTimeMillis();
            this.provisioningId = provisioningId;
        }

        @Override
        public Node call() throws Exception {
            try {
                ProvisionedVirtualMachineSlave result = template.provision(Datacenter.this, plannedNodeName);

                // Links the agent to the provisioning attempt in the cloud statistics
                result.setProvisioningId(provisioningId);

                long duration = System.currentTimeMillis() - startTime;
                LOGGER.log(
                        Level.INFO,
                        "Node {0} provisioned in {1}ms. Jenkins will add node and begin launch phase.",
                        new Object[] {plannedNodeName, duration});

                getStatistics().recordProvisioningSuccess(duration);

                // Jenkins core adds the node and triggers the launch phase
                return result;
            } catch (Exception e) {
                getStatistics().recordProvisioningFailure(e.getMessage());
                throw e;
            }
        }
    }

    private int getCurrentSlaveCount() {
        int count = 0;
        String description = getDatacenterDescription();
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof ProxmoxAgent
                    && description.equals(((ProxmoxAgent) node).getDatacenterDescription())) {
                count++;
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

    public String getCredentialsId() {
        return credentialsId;
    }

    public Boolean getIgnoreSSL() {
        return ignoreSSL;
    }

    public List<ProxmoxCloudSlaveTemplate> getTemplates() {
        return templates != null ? templates : Collections.emptyList();
    }

    @DataBoundSetter
    public void setTemplates(List<ProxmoxCloudSlaveTemplate> templates) {
        this.templates = templates != null ? new ArrayList<>(templates) : new ArrayList<>();
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    @DataBoundSetter
    public void setInstanceCap(int instanceCap) {
        this.instanceCap = Math.max(0, instanceCap);
    }

    /**
     * Identifies this datacenter in agent configurations, so it must stay stable: {@code user@realm - hostname}.
     * Agents are matched with {@link #findByDescription(String)}.
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
            // Credential currently unavailable: keep the last known value so agents stay attached,
            // or use the credential id as a stable placeholder (also when no Jenkins runs, in unit tests).
            ProxmoxAuth last = resolvedAuth;
            return last != null ? last.getUserAtRealm() : credentialsId;
        }
    }

    /**
     * Finds the datacenter an agent refers to. Matches the description exactly; if that fails (for
     * example because the credential's user changed), falls back to the hostname part of the
     * description when exactly one datacenter has that hostname.
     *
     * @return the datacenter or {@code null}
     */
    public static Datacenter findByDescription(String description) {
        if (Util.fixEmptyAndTrim(description) == null) {
            return null;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return null;
        }
        Datacenter byHostname = null;
        int hostnameMatches = 0;
        int separator = description.lastIndexOf(" - ");
        String hostnamePart = separator >= 0 ? description.substring(separator + 3) : null;
        for (Cloud cloud : jenkins.clouds) {
            if (!(cloud instanceof Datacenter)) {
                continue;
            }
            Datacenter datacenter = (Datacenter) cloud;
            if (description.equals(datacenter.getDatacenterDescription())) {
                return datacenter;
            }
            if (hostnamePart != null && hostnamePart.equals(datacenter.getHostname())) {
                byHostname = datacenter;
                hostnameMatches++;
            }
        }
        if (hostnameMatches == 1) {
            LOGGER.log(
                    Level.FINE,
                    "No datacenter matches '{0}' exactly; using the only one with that hostname: {1}",
                    new Object[] {description, byHostname.getDatacenterDescription()});
            return byHostname;
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Statistics and health
    // ------------------------------------------------------------------

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

    /** @return an HTML summary of the connection, capacity, agents and nodes of this datacenter */
    public String getStatusSummary() {
        updateStatistics();
        ProxmoxCloudStatistics stats = getStatistics();

        StringBuilder summary = new StringBuilder();
        summary.append("<div style='font-family: monospace; font-size: 12px;'>");
        summary.append("<strong>")
                .append(Util.escape(getDatacenterDescription()))
                .append("</strong><br>");

        // Connection Status
        if (stats.isDatacenterReachable()) {
            summary.append("🟢 <span style='color: green;'>Connected</span>");
        } else {
            summary.append("🔴 <span style='color: red;'>Disconnected - ")
                    .append(Util.escape(Util.fixNull(stats.getLastErrorMessage())))
                    .append("</span>");
        }

        summary.append("<br><br>");

        // Capacity Information
        summary.append("<strong>Capacity:</strong> ")
                .append(stats.getCurrentSlaveCount())
                .append("/")
                .append(instanceCap)
                .append(" slaves (")
                .append(Math.max(0, instanceCap - stats.getCurrentSlaveCount()))
                .append(" available)<br>");

        // Slave Status
        summary.append("<strong>Slaves:</strong> ")
                .append(stats.getOnlineSlaves())
                .append(" online, ")
                .append(stats.getOfflineSlaves())
                .append(" offline, ")
                .append(stats.getTemporarilyOfflineSlaves())
                .append(" temp-offline");

        if (stats.getProvisioningSlaves() > 0) {
            summary.append(", ").append(stats.getProvisioningSlaves()).append(" provisioning");
        }
        summary.append("<br>");

        // Provisioning Statistics
        if (stats.getTotalProvisioningAttempts() > 0) {
            summary.append("<strong>Provisioning:</strong> ")
                    .append(String.format("%.1f%% success rate ", stats.getSuccessRate()))
                    .append("(")
                    .append(stats.getSuccessfulProvisionings())
                    .append("/")
                    .append(stats.getTotalProvisioningAttempts())
                    .append(" attempts)<br>");
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
                summary.append("&nbsp;&nbsp;• ")
                        .append(Util.escape(Util.fixNull(health.nodeName)))
                        .append(": ");
                if (health.online) {
                    summary.append(String.format(
                            "🟢 Online (CPU: %.0f%%, RAM: %.0f%%, VMs: %d)",
                            health.cpuUsage, health.memoryUsage, health.runningVMs));
                } else {
                    summary.append("🔴 ").append(Util.escape(Util.fixNull(health.status)));
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

    // ------------------------------------------------------------------
    // Connection to Proxmox
    // ------------------------------------------------------------------

    /**
     * Resolves the credentials, caching the result for {@link #AUTH_CACHE_MS}, so dropdowns and
     * agent launches do not hit the credentials store on every call.
     *
     * @throws IllegalStateException if the configured credential does not exist or has an unsupported type
     */
    ProxmoxAuth resolveAuth() {
        if (credentialsId == null) {
            return ProxmoxAuth.usernamePassword(username, realm, password);
        }
        ProxmoxAuth cached = resolvedAuth;
        if (cached != null && System.currentTimeMillis() - resolvedAuthAtMs < AUTH_CACHE_MS) {
            return cached;
        }
        ProxmoxAuth auth = resolveAuth(username, realm, password, credentialsId);
        resolvedAuth = auth;
        resolvedAuthAtMs = System.currentTimeMillis();
        return auth;
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
            String effectiveRealm = Util.fixEmptyAndTrim(realm) == null ? DEFAULT_REALM : realm.trim();
            return ProxmoxAuth.usernamePassword(c.getUsername(), effectiveRealm, c.getPassword());
        }
        if (credentials instanceof StringCredentials) {
            ProxmoxAuth token = ProxmoxAuth.apiToken(((StringCredentials) credentials).getSecret());
            if (!token.isValidFormat()) {
                throw new IllegalStateException("Proxmox credential '" + id
                        + "' is not an API token; expected the form user@realm!tokenid=secret");
            }
            return token;
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
        ConnectorPool.Key own = connectorKey();
        Set<ConnectorPool.Key> inUse = new HashSet<>();
        inUse.add(own);
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof Datacenter && cloud != this) {
                    try {
                        inUse.add(((Datacenter) cloud).connectorKey());
                    } catch (IllegalStateException e) {
                        // A datacenter with a broken credential has no connector to keep.
                    }
                }
            }
        }
        return ConnectorPool.acquire(own, inUse);
    }

    /** Closes the shared HTTP clients when Jenkins shuts down. */
    @Terminator
    public static void closeConnectors() {
        ConnectorPool.closeAll();
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

    // ------------------------------------------------------------------
    // Termination and cleanup of provisioned agents
    // ------------------------------------------------------------------

    /**
     * @return whether the computer is an agent of this datacenter whose virtual machine was cloned
     *     by the cloud. Agents that were bound to an existing virtual machine by hand are never
     *     terminated, because that deletes the virtual machine.
     */
    public boolean canTerminate(Computer computer) {
        if (!(computer instanceof ProvisionedVirtualMachineSlaveComputer)) {
            return false;
        }
        ProvisionedVirtualMachineSlave vmSlave = ((ProvisionedVirtualMachineSlaveComputer) computer).getNode();
        return vmSlave != null && getDatacenterDescription().equals(vmSlave.getDatacenterDescription());
    }

    /** Stops and deletes the cloned virtual machine of a provisioned agent and removes the agent from Jenkins. */
    public void terminate(Computer computer) {
        ProvisionedVirtualMachineSlave vmSlave =
                canTerminate(computer) ? ((ProvisionedVirtualMachineSlaveComputer) computer).getNode() : null;
        if (vmSlave == null) {
            LOGGER.log(
                    Level.WARNING,
                    "Cannot terminate computer: {0} - not provisioned by this cloud instance",
                    computer.getName());
            return;
        }

        Integer vmId = vmSlave.getVirtualMachineId();
        String nodeName = vmSlave.getDatacenterNode();

        LOGGER.log(Level.INFO, "Terminating VM slave: {0} (VM ID: {1}) on node: {2}", new Object[] {
            vmSlave.getNodeName(), String.valueOf(vmId), nodeName
        });

        try {
            getStatistics().recordTermination();

            Connector proxmoxApi = proxmoxInstance();

            // Stop the VM if it is running; the deletion is attempted even if stopping fails
            if (proxmoxApi.isQemuMachineRunning(nodeName, vmId)) {
                LOGGER.log(Level.INFO, "Stopping VM {0} (ID: {1}) before deletion", new Object[] {
                    vmSlave.getNodeName(), String.valueOf(vmId)
                });
                try {
                    String stopTask = proxmoxApi.stopQemuMachine(nodeName, vmId);
                    JSONObject stopTaskResult = proxmoxApi.waitForTaskToFinish(nodeName, stopTask);
                    if (!Connector.isTaskSuccessful(stopTaskResult)) {
                        LOGGER.log(Level.WARNING, "VM stop failed for {0}: {1}", new Object[] {
                            vmSlave.getNodeName(), VirtualMachineLauncher.describe(stopTaskResult)
                        });
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    LOGGER.log(Level.WARNING, "Interrupted while stopping VM of " + vmSlave.getNodeName(), e);
                } catch (LoginException | IOException | RuntimeException e) {
                    LOGGER.log(
                            Level.WARNING,
                            "VM stop task failed for " + vmSlave.getNodeName() + ", but continuing with deletion",
                            e);
                }
            }

            LOGGER.log(
                    Level.INFO, "Deleting VM {0} (ID: {1})", new Object[] {vmSlave.getNodeName(), String.valueOf(vmId)
                    });
            proxmoxApi.deleteQemuMachine(nodeName, vmId);

            Jenkins.get().removeNode(vmSlave);

            LOGGER.log(Level.INFO, "Successfully terminated and cleaned up VM slave: {0} (ID: {1})", new Object[] {
                vmSlave.getNodeName(), String.valueOf(vmId)
            });

        } catch (LoginException | IOException | RuntimeException e) {
            LOGGER.log(
                    Level.SEVERE,
                    "Failed to terminate VM slave: " + vmSlave.getNodeName() + " (ID: " + vmId + ")",
                    e);

            // Even if the VM deletion fails, remove the agent from Jenkins to prevent orphaned nodes
            try {
                Jenkins.get().removeNode(vmSlave);
                LOGGER.log(
                        Level.INFO,
                        "Removed orphaned Jenkins node: {0} after VM deletion failure",
                        vmSlave.getNodeName());
            } catch (IOException | RuntimeException removeException) {
                LOGGER.log(Level.SEVERE, "Failed to remove Jenkins node after VM deletion failure", removeException);
            }
        }
    }

    /**
     * Removes provisioned agents whose virtual machine no longer exists in Proxmox, and deletes
     * cloned virtual machines that lost their agent. Agents that were bound to an existing virtual
     * machine by hand are never touched.
     */
    public void cleanupOrphanedNodes() {
        LOGGER.log(Level.FINE, "Starting cleanup of orphaned nodes for datacenter: {0}", getDatacenterDescription());

        List<ProvisionedVirtualMachineSlave> nodesToRemove = new ArrayList<>();
        String description = getDatacenterDescription();

        for (Node node : Jenkins.get().getNodes()) {
            if (!(node instanceof ProvisionedVirtualMachineSlave)) {
                continue;
            }
            ProvisionedVirtualMachineSlave vmSlave = (ProvisionedVirtualMachineSlave) node;
            if (!description.equals(vmSlave.getDatacenterDescription())) {
                continue;
            }
            Computer computer = vmSlave.toComputer();
            if (computer == null) {
                LOGGER.log(Level.WARNING, "Found orphaned node with null computer: {0}", vmSlave.getNodeName());
                nodesToRemove.add(vmSlave);
            } else if (shouldCleanupNode(vmSlave, computer)) {
                nodesToRemove.add(vmSlave);
            }
        }

        for (ProvisionedVirtualMachineSlave nodeToRemove : nodesToRemove) {
            try {
                LOGGER.log(Level.INFO, "Removing orphaned node: {0} (VM ID: {1})", new Object[] {
                    nodeToRemove.getNodeName(), String.valueOf(nodeToRemove.getVirtualMachineId())
                });

                Computer computer = nodeToRemove.toComputer();
                if (computer != null) {
                    computer.disconnect(new ProxmoxOfflineCause("Node cleanup - VM no longer accessible"));
                }

                Jenkins.get().removeNode(nodeToRemove);
                getStatistics().recordTermination();

            } catch (IOException | RuntimeException e) {
                LOGGER.log(Level.WARNING, "Failed to remove orphaned node: " + nodeToRemove.getNodeName(), e);
            }
        }

        if (nodesToRemove.isEmpty()) {
            LOGGER.log(Level.FINE, "No orphaned nodes found for datacenter: {0}", description);
        } else {
            LOGGER.log(Level.INFO, "Cleaned up {0} orphaned nodes for datacenter: {1}", new Object[] {
                nodesToRemove.size(), description
            });
        }

        // Also clean up cloned VMs in Proxmox that lost their Jenkins node
        cleanupOrphanedVMs();
    }

    /**
     * Deletes cloned virtual machines that no longer have a Jenkins node, for example because the
     * deletion in Proxmox failed when the node was removed (e.g. during a Jenkins restart).
     */
    private void cleanupOrphanedVMs() {
        if (getTemplates().isEmpty()) {
            return;
        }
        LOGGER.log(
                Level.FINE,
                "Starting cleanup of orphaned VMs in Proxmox for datacenter: {0}",
                getDatacenterDescription());

        try {
            Connector proxmoxApi = proxmoxInstance();

            // All virtual machines of this datacenter that still have a Jenkins node
            Map<Integer, ProxmoxAgent> jenkinsVMs = new HashMap<>();
            String description = getDatacenterDescription();
            for (Node node : Jenkins.get().getNodes()) {
                if (node instanceof ProxmoxAgent) {
                    ProxmoxAgent agent = (ProxmoxAgent) node;
                    if (description.equals(agent.getDatacenterDescription())) {
                        jenkinsVMs.put(agent.getVirtualMachineId(), agent);
                    }
                }
            }

            for (ProxmoxCloudSlaveTemplate template : getTemplates()) {
                try {
                    cleanupOrphanedVMsForTemplate(proxmoxApi, template, jenkinsVMs);
                } catch (LoginException | IOException | RuntimeException e) {
                    LOGGER.log(
                            Level.WARNING,
                            "Failed to cleanup orphaned VMs for template: " + template.getTemplateName(),
                            e);
                }
            }

        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to cleanup orphaned VMs in Proxmox", e);
        }
    }

    /** @return the identifier of this Jenkins instance that is part of the names of cloned VMs. */
    private static String getJenkinsInstanceId() {
        try {
            String rootUrl = Jenkins.get().getRootUrl();
            if (rootUrl != null && !rootUrl.isEmpty()) {
                int hash = rootUrl.hashCode();
                return String.format("%08x", hash & 0xFFFFFFFFL);
            }
            String version = Jenkins.VERSION;
            long startTime = Jenkins.get().getInitLevel().ordinal();
            int hash = (version + startTime).hashCode();
            return String.format("%08x", hash & 0xFFFFFFFFL);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Could not get Jenkins instance identifier, using default", e);
            return "00000000";
        }
    }

    /** Names of cloned VMs follow the pattern {@code templateName-instanceId-timestamp}. */
    private boolean belongsToThisInstance(String vmName, String templateName) {
        return vmName != null && vmName.startsWith(templateName + "-" + getJenkinsInstanceId() + "-");
    }

    private void cleanupOrphanedVMsForTemplate(
            Connector proxmoxApi, ProxmoxCloudSlaveTemplate template, Map<Integer, ProxmoxAgent> jenkinsVMs)
            throws LoginException, IOException {

        String nodeName = template.getDatacenterNode();
        if (nodeName == null || nodeName.isEmpty()) {
            LOGGER.log(Level.FINE, "Template {0} has no datacenter node configured", template.getTemplateName());
            return;
        }

        for (Map.Entry<Integer, String> entry : proxmoxApi.getQemuMachines(nodeName).entrySet()) {
            Integer vmId = entry.getKey();
            String vmName = entry.getValue();

            // Never delete the template VM itself
            if (String.valueOf(vmId).equals(template.getTemplateVmId())) {
                continue;
            }

            // Only clones that this Jenkins instance created from this template are candidates
            if (!belongsToThisInstance(vmName, template.getTemplateName())) {
                continue;
            }

            // A clone with a Jenkins node is cleaned up when that node is terminated
            if (jenkinsVMs.containsKey(vmId)) {
                continue;
            }

            // The clone is orphaned; a running one is only deleted if the template asks for it
            try {
                boolean isRunning = proxmoxApi.isQemuMachineRunning(nodeName, vmId);
                if (isRunning && !template.getForceCleanupRunningVMs()) {
                    LOGGER.log(
                            Level.WARNING,
                            "Found orphaned VM {0} (ID: {1}) but it is running - not deleting"
                                    + " (forceCleanupRunningVMs=false)",
                            new Object[] {vmName, String.valueOf(vmId)});
                    continue;
                }
            } catch (LoginException | IOException | RuntimeException statusEx) {
                // Without knowing the state the VM is treated as running
                LOGGER.log(Level.FINE, "Could not get status for VM {0} (ID: {1}): {2}", new Object[] {
                    vmName, String.valueOf(vmId), statusEx.getMessage()
                });
                if (!template.getForceCleanupRunningVMs()) {
                    continue;
                }
            }

            LOGGER.log(Level.INFO, "Deleting orphaned VM in Proxmox: {0} (ID: {1}) on node {2}", new Object[] {
                vmName, String.valueOf(vmId), nodeName
            });

            try {
                proxmoxApi.deleteQemuMachine(nodeName, vmId);
                getStatistics().recordTermination();
            } catch (LoginException | IOException | RuntimeException deleteEx) {
                LOGGER.log(Level.WARNING, "Failed to delete orphaned VM " + vmName + " (ID: " + vmId + ")", deleteEx);
            }
        }
    }

    /**
     * @return whether the computer runs a build or could take a queued one. Such agents are kept,
     *     which matters for Pipeline builds that resume after a Jenkins restart.
     */
    private boolean hasPotentiallyResumableBuilds(Computer computer) {
        if (computer == null) {
            return false;
        }

        try {
            for (Executor executor : computer.getExecutors()) {
                if (executor.isBusy()) {
                    return true;
                }
            }

            Node node = computer.getNode();
            if (node != null) {
                for (Queue.BuildableItem item : Jenkins.get().getQueue().getBuildableItems()) {
                    if (node.canTake(item) == null) {
                        return true;
                    }
                }
            }

            return false;

        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Error checking for resumable builds on " + computer.getName(), e);
            // When in doubt, assume there might be resumable builds
            return true;
        }
    }

    /**
     * @return whether the agent's virtual machine is gone. Only a successful listing that lacks the
     *     virtual machine proves that; an unreachable or failing Proxmox never causes a cleanup.
     */
    private boolean shouldCleanupNode(ProvisionedVirtualMachineSlave vmSlave, Computer computer) {
        try {
            if (hasPotentiallyResumableBuilds(computer)) {
                return false;
            }

            // Agents that a user took offline are left alone
            if (computer.isOffline() && computer.getOfflineCause() instanceof OfflineCause.UserCause) {
                return false;
            }

            String nodeName = vmSlave.getDatacenterNode();
            Integer vmId = vmSlave.getVirtualMachineId();
            if (nodeName == null || vmId == null) {
                LOGGER.log(Level.WARNING, "Node {0} has invalid datacenter node or VM ID", vmSlave.getNodeName());
                return true;
            }

            boolean exists = proxmoxInstance().getQemuMachines(nodeName).containsKey(vmId);
            if (!exists) {
                LOGGER.log(Level.INFO, "VM {0} (ID: {1}) on node {2} is missing from Proxmox", new Object[] {
                    vmSlave.getNodeName(), String.valueOf(vmId), nodeName
                });
            }
            return !exists;

        } catch (LoginException | IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Error checking node " + vmSlave.getNodeName() + " for cleanup", e);
            return false; // When in doubt, don't cleanup
        }
    }

    /** Offline cause for agents that are removed by the cleanup. */
    private static class ProxmoxOfflineCause extends OfflineCause {
        private final String reason;

        ProxmoxOfflineCause(String reason) {
            this.reason = reason;
        }

        @Override
        public String toString() {
            return reason;
        }
    }

    /** Makes this object available as {@code status} in URLs below the cloud. */
    public Object getStatus() {
        return this;
    }

    /** Manual cleanup of orphaned agents and virtual machines, triggered from the web UI. */
    @POST
    public void doCleanupOrphans(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        LOGGER.log(Level.INFO, "Manual cleanup of orphaned nodes requested for: {0}", getDatacenterDescription());
        cleanupOrphanedNodes();

        rsp.sendRedirect2(Jenkins.get().getRootUrl() + "manage/cloud");
    }

    /** Removes this cloud from the Jenkins configuration. */
    @POST
    public void doDelete(StaplerRequest2 req, StaplerResponse2 rsp) throws Exception {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        LOGGER.log(Level.INFO, "Deleting Proxmox cloud: {0}", getDatacenterDescription());

        Jenkins jenkins = Jenkins.get();
        List<Cloud> clouds = new ArrayList<>(jenkins.clouds);
        clouds.remove(this);
        jenkins.clouds.replaceBy(clouds);
        jenkins.save();

        rsp.sendRedirect("../");
    }

    @Extension
    @Symbol("datacenter")
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

        @POST
        public FormValidation doCheckHostname(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (value != null && value.contains("@")) {
                return FormValidation.error("Hostname must not contain user information; use the fields below");
            }
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

        @POST
        public FormValidation doCheckRealm(@QueryParameter String value, @QueryParameter String credentialsId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            if (Util.fixEmptyAndTrim(credentialsId) != null) {
                return FormValidation.ok();
            }
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

        @POST
        public FormValidation doCheckCloudHealth(@QueryParameter String datacenterDescription) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (Util.fixEmptyAndTrim(datacenterDescription) == null) {
                return FormValidation.warning("No datacenter selected");
            }
            Datacenter datacenter = findByDescription(datacenterDescription);
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

            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Health check failed for " + datacenterDescription, e);
                return FormValidation.error("Health check failed: " + e.getMessage());
            }
        }

        @POST
        public FormValidation doGetDetailedStatistics(@QueryParameter String datacenterDescription) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (Util.fixEmptyAndTrim(datacenterDescription) == null) {
                return FormValidation.warning("No datacenter selected");
            }
            Datacenter datacenter = findByDescription(datacenterDescription);
            if (datacenter == null) {
                return FormValidation.error("Datacenter not found: " + datacenterDescription);
            }

            try {
                datacenter.updateStatistics();
                return FormValidation.ok(datacenter.getDetailedStatisticsReport());
            } catch (RuntimeException e) {
                LOGGER.log(Level.SEVERE, "Failed to get statistics for " + datacenterDescription, e);
                return FormValidation.error("Failed to get statistics: " + e.getMessage());
            }
        }
    }
}
