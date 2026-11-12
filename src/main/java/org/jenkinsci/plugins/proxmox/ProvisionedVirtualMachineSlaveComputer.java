package org.jenkinsci.plugins.proxmox;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.slaves.AbstractCloudComputer;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

/**
 * The computer of a {@link ProvisionedVirtualMachineSlave}. Deleting it terminates the agent, which
 * stops and deletes the cloned virtual machine.
 */
public class ProvisionedVirtualMachineSlaveComputer extends AbstractCloudComputer<ProvisionedVirtualMachineSlave>
        implements TrackedItem {

    /** Kept for the cloud statistics, which ask for the id before the node is known to Jenkins. */
    private final ProvisionedVirtualMachineSlave slave;

    public ProvisionedVirtualMachineSlaveComputer(ProvisionedVirtualMachineSlave slave) {
        super(slave);
        this.slave = slave;
    }

    /**
     * Agents that were disabled or taken offline by a user stay offline; they are not relaunched
     * (which would start the virtual machine) until the user brings them back.
     */
    @Override
    public void tryReconnect() {
        String reason = VirtualMachineSlaveComputer.reasonToStayOffline(this);
        if (reason != null) {
            getListener().getLogger().println("INFO: " + reason + ", skipping reconnection attempt");
            return;
        }
        super.tryReconnect();
    }

    /** Counts the build if the template limits the number of builds per agent. */
    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        super.taskCompleted(executor, task, durationMS);

        ProvisionedVirtualMachineSlave node = getNode();
        if (node == null || node.getLimitedBuildsCount() <= 0) {
            return;
        }
        int limit = node.getLimitedBuildsCount();
        int executed = node.getBuildsExecuted() + 1;
        getListener().getLogger().println("INFO: Build " + executed + " of " + limit + " completed on this agent");
        if (executed >= limit) {
            getListener().getLogger().println("INFO: Limited builds threshold reached, agent will be deprovisioned");
        }
        // Removes the agent, together with its virtual machine, once the limit is reached.
        node.incrementBuildsExecuted();
    }

    /** {@link TrackedItem} implementation for the cloud statistics plugin. */
    @Override
    @CheckForNull
    public ProvisioningActivity.Id getId() {
        ProvisionedVirtualMachineSlave node = getNode();
        // Computers that are not yet added to Jenkins have no node
        return node != null ? node.getId() : slave.getId();
    }
}
