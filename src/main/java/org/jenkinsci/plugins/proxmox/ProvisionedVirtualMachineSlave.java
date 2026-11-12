package org.jenkinsci.plugins.proxmox;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.StaplerRequest2;

/**
 * An agent that a {@link ProxmoxCloudSlaveTemplate} provisioned on demand by cloning a virtual
 * machine. Unlike a {@link VirtualMachineSlave}, which is bound to an existing virtual machine by
 * hand, it owns its virtual machine: removing the agent stops and deletes the clone.
 *
 * <p>Being an {@link AbstractCloudSlave} marks it as a cloud agent for Jenkins and other plugins.
 * Configuration as Code, for example, does not export cloud agents and keeps them when a
 * configuration is applied that does not list them.
 */
public class ProvisionedVirtualMachineSlave extends AbstractCloudSlave implements ProxmoxAgent, TrackedItem {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(ProvisionedVirtualMachineSlave.class.getName());

    private final String datacenterDescription;
    private final String datacenterNode;
    private final Integer virtualMachineId;
    private transient int limitedBuildsCount = 0;
    private transient int buildsExecuted = 0;

    /** Identifies the agent for the cloud statistics plugin. */
    private ProvisioningActivity.Id provisioningId;

    public ProvisionedVirtualMachineSlave(
            String name,
            String nodeDescription,
            String remoteFS,
            String numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher delegateLauncher,
            RetentionStrategy<?> retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties,
            String datacenterDescription,
            String datacenterNode,
            Integer virtualMachineId,
            String snapshotName,
            Boolean startVM,
            int startupWaitingPeriodSeconds)
            throws IOException, Descriptor.FormException {
        this(
                name,
                nodeDescription,
                remoteFS,
                VirtualMachineSlave.parseExecutors(numExecutors),
                mode,
                labelString,
                // A clone is thrown away with its agent, so it is never reverted to a snapshot.
                new VirtualMachineLauncher(
                        delegateLauncher,
                        datacenterDescription,
                        datacenterNode,
                        virtualMachineId,
                        snapshotName,
                        startVM,
                        startupWaitingPeriodSeconds,
                        RevertPolicy.NEVER),
                retentionStrategy,
                nodeProperties,
                datacenterDescription,
                datacenterNode,
                virtualMachineId);
    }

    private ProvisionedVirtualMachineSlave(
            String name,
            String nodeDescription,
            String remoteFS,
            int numExecutors,
            Mode mode,
            String labelString,
            ComputerLauncher launcher,
            RetentionStrategy<?> retentionStrategy,
            List<? extends NodeProperty<?>> nodeProperties,
            String datacenterDescription,
            String datacenterNode,
            Integer virtualMachineId)
            throws IOException, Descriptor.FormException {
        super(name, remoteFS, launcher);
        setNodeDescription(nodeDescription);
        setNumExecutors(numExecutors);
        setMode(mode);
        setLabelString(labelString);
        if (retentionStrategy != null) {
            setRetentionStrategy(retentionStrategy);
        }
        if (nodeProperties != null) {
            getNodeProperties().replaceBy(nodeProperties);
        }
        this.datacenterDescription = datacenterDescription;
        this.datacenterNode = datacenterNode;
        this.virtualMachineId = virtualMachineId;
    }

    /**
     * Takes over an agent that an older version of the plugin provisioned. Back then provisioned
     * agents were {@link VirtualMachineSlave}s that were marked by their provisioning id.
     */
    static ProvisionedVirtualMachineSlave migrate(VirtualMachineSlave old, ProvisioningActivity.Id provisioningId)
            throws IOException, Descriptor.FormException {
        ProvisionedVirtualMachineSlave migrated = new ProvisionedVirtualMachineSlave(
                old.getNodeName(),
                old.getNodeDescription(),
                old.getRemoteFS(),
                old.getNumExecutors(),
                old.getMode(),
                old.getLabelString(),
                old.getLauncher(),
                old.getRetentionStrategy(),
                old.getNodeProperties().toList(),
                old.getDatacenterDescription(),
                old.getDatacenterNode(),
                old.getVirtualMachineId());
        migrated.setProvisioningId(provisioningId);
        return migrated;
    }

    @Override
    public String getDatacenterDescription() {
        return datacenterDescription;
    }

    @Override
    public String getDatacenterNode() {
        return datacenterNode;
    }

    @Override
    public Integer getVirtualMachineId() {
        return virtualMachineId;
    }

    /** {@link TrackedItem} implementation for the cloud statistics plugin. */
    @Override
    @CheckForNull
    public ProvisioningActivity.Id getId() {
        return provisioningId;
    }

    public void setProvisioningId(ProvisioningActivity.Id id) {
        this.provisioningId = id;
    }

    public void setLimitedBuildsCount(int count) {
        this.limitedBuildsCount = count;
    }

    public int getLimitedBuildsCount() {
        return limitedBuildsCount;
    }

    public int getBuildsExecuted() {
        return buildsExecuted;
    }

    /**
     * Counts a finished build and removes the agent, together with its cloned virtual machine,
     * once the configured number of builds was reached.
     */
    public void incrementBuildsExecuted() {
        buildsExecuted++;
        if (limitedBuildsCount <= 0 || buildsExecuted < limitedBuildsCount) {
            return;
        }
        try {
            deleteVirtualMachine();

            // Close the channel before removing the node to prevent WebSocket timeout errors
            Computer computer = toComputer();
            VirtualChannel channel = computer == null ? null : computer.getChannel();
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException e) {
                    LOGGER.log(Level.WARNING, "Failed to close the channel of " + getNodeName() + " before removal", e);
                }
            }

            Jenkins.get().removeNode(this);
            LOGGER.log(Level.INFO, "Agent {0} deprovisioned after {1} builds", new Object[] {
                getNodeName(), limitedBuildsCount
            });
        } catch (IOException | RuntimeException e) {
            // Log, but do not fail the build
            LOGGER.log(Level.WARNING, "Failed to deprovision agent " + getNodeName() + " after limited builds", e);
        }
    }

    /** Stops and deletes the cloned virtual machine of this agent; failures are logged. */
    private void deleteVirtualMachine() {
        Datacenter datacenter = Datacenter.findByDescription(datacenterDescription);
        if (datacenter == null || virtualMachineId == null || datacenterNode == null) {
            return;
        }
        try {
            Connector pveApi = datacenter.proxmoxInstance();
            if (pveApi.isQemuMachineRunning(datacenterNode, virtualMachineId)) {
                LOGGER.log(Level.INFO, "Stopping VM {0} before deletion", virtualMachineId.toString());
                String stopTask = pveApi.stopQemuMachine(datacenterNode, virtualMachineId);
                JSONObject stopResult = pveApi.waitForTaskToFinish(datacenterNode, stopTask);
                LOGGER.log(Level.INFO, "VM {0} stop finished: {1}", new Object[] {
                    virtualMachineId.toString(), VirtualMachineLauncher.describe(stopResult)
                });
            }
            String deleteTask = pveApi.deleteQemuMachine(datacenterNode, virtualMachineId);
            LOGGER.log(Level.INFO, "VM {0} deletion initiated (task: {1})", new Object[] {
                virtualMachineId.toString(), deleteTask
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Interrupted while deleting VM " + virtualMachineId, e);
        } catch (LoginException | IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Failed to stop/delete VM " + virtualMachineId + " from Proxmox", e);
        }
    }

    @Override
    public ProvisionedVirtualMachineSlaveComputer createComputer() {
        return new ProvisionedVirtualMachineSlaveComputer(this);
    }

    /** Called when the agent is deleted: stops and deletes its cloned virtual machine. */
    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        Datacenter datacenter = Datacenter.findByDescription(datacenterDescription);
        if (datacenter == null) {
            listener.getLogger().println("Warning: Cannot terminate - datacenter not found: " + datacenterDescription);
            return;
        }
        Computer computer = toComputer();
        if (computer == null) {
            listener.getLogger().println("Warning: Cannot terminate - computer is null");
            return;
        }
        listener.getLogger().println("Terminating VM " + virtualMachineId + " through datacenter");
        datacenter.terminate(computer);
    }

    /**
     * A provisioned agent is defined by its template, so submitting its configuration page changes
     * nothing. Rebuilding it from the form would also lose the link to its cloned virtual machine.
     */
    @Override
    public Node reconfigure(@NonNull StaplerRequest2 req, net.sf.json.JSONObject form) {
        return this;
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Agent provisioned from a template of a Proxmox datacenter";
        }

        /** Only the cloud creates these agents; they are not offered on the "New node" page. */
        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
