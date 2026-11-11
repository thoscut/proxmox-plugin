package org.jenkinsci.plugins.proxmox;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.util.Futures;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

public class VirtualMachineSlaveComputer extends AbstractCloudComputer<VirtualMachineSlave> implements TrackedItem {

    private static final Logger LOGGER = Logger.getLogger(VirtualMachineSlaveComputer.class.getName());

    private final AtomicBoolean isRevertingSnapshot = new AtomicBoolean(false);

    /** The executor thread that is reverting the snapshot; only it may relaunch the agent meanwhile. */
    private volatile Thread revertingThread;

    /** Kept for the cloud statistics, which ask for the id before the node is known to Jenkins. */
    private final VirtualMachineSlave slave;

    public VirtualMachineSlaveComputer(VirtualMachineSlave slave) {
        super(slave);
        this.slave = slave;
    }

    /**
     * Agents that were disabled or taken offline by a user stay offline; they are not relaunched
     * (which would revert and start the virtual machine) until the user brings them back.
     */
    @Override
    public void tryReconnect() {
        if (!isAcceptingTasks()) {
            getListener().getLogger().println("INFO: Node is disabled, skipping reconnection attempt");
            return;
        }
        if (isOffline() && getOfflineCause() instanceof OfflineCause.UserCause) {
            getListener().getLogger().println("INFO: Node is manually offline, skipping reconnection attempt");
            return;
        }
        super.tryReconnect();
    }

    /**
     * While a snapshot is being reverted, the agent is offline on purpose. Retention strategies and
     * reconnect attempts would otherwise start the VM (or an SSH launch) in the middle of the rollback.
     */
    @Override
    protected Future<?> _connect(boolean forceReconnect) {
        if (isRevertingSnapshot.get() && Thread.currentThread() != revertingThread) {
            getListener().getLogger().println("INFO: connect requested during snapshot revert - ignoring");
            return Futures.precomputed(null);
        }
        return super._connect(forceReconnect);
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);

        final Node node = getNode();
        if (!(node instanceof VirtualMachineSlave)) {
            return;
        }
        final VirtualMachineSlave slave = (VirtualMachineSlave) node;
        final ComputerLauncher nodeLauncher = slave.getLauncher();
        if (!(nodeLauncher instanceof VirtualMachineLauncher)) {
            return;
        }
        final VirtualMachineLauncher launcher = (VirtualMachineLauncher) nodeLauncher;

        // The launcher's policy is the source of truth; the node attribute only mirrors it.
        if (!launcher.isLaunchSupported()
                || launcher.effectiveRevertPolicy() != VirtualMachineLauncher.RevertPolicy.BEFORE_JOB) {
            return;
        }

        // Two tasks accepted at once (more than one executor) must not revert the same VM twice,
        // nor may the second one run while the first revert is tearing the agent down.
        if (!isRevertingSnapshot.compareAndSet(false, true)) {
            abort(executor, "Another task is already reverting this virtual machine; "
                    + "use a single executor with the revert policy 'Before every job'", null);
            return;
        }

        revertingThread = Thread.currentThread();
        try {
            try {
                disconnect(OfflineCause.create(Messages._VirtualMachineSlaveComputer_disconnectBeforeSnapshotRevert()))
                        .get();
            } catch (ExecutionException e) {
                abort(executor, "Disconnecting the agent failed: " + causeMessage(e), e);
                return;
            }
            getListener().getLogger().println("INFO: agent disconnected");

            launcher.revertSnapshot(this, getListener());
            getListener().getLogger().println("INFO: snapshot reverted");

            // Go through connect() so Jenkins knows a launch is in progress (isConnecting).
            try {
                connect(true).get();
            } catch (ExecutionException e) {
                abort(executor, "Agent did not reconnect after the snapshot revert: " + causeMessage(e), e);
                return;
            }
            if (getChannel() == null) {
                abort(executor, "Agent did not reconnect after the snapshot revert", null);
                return;
            }
            getListener().getLogger().println("INFO: agent launched");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort(executor, "Interrupted while reverting the snapshot", e);
        } catch (Exception e) {
            abort(executor, "Snapshot revert failed: " + e.getMessage(), e);
        } finally {
            revertingThread = null;
            isRevertingSnapshot.set(false);
        }
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        super.taskCompleted(executor, task, durationMS);
        afterTask();
    }

    private void afterTask() {
        final Node node = getNode();
        if (!(node instanceof VirtualMachineSlave)) {
            return;
        }
        final VirtualMachineSlave vmSlave = (VirtualMachineSlave) node;

        if (vmSlave.getLimitedBuildsCount() > 0) {
            int limit = vmSlave.getLimitedBuildsCount();
            int executed = vmSlave.getBuildsExecuted() + 1;
            getListener().getLogger().println("INFO: Build " + executed + " of " + limit + " completed on this agent");
            if (executed >= limit) {
                getListener().getLogger().println("INFO: Limited builds threshold reached, agent will be deprovisioned");
            }
            // Removes the agent once the limit is reached, so there is nothing left to revert then.
            vmSlave.incrementBuildsExecuted();
            if (executed >= limit) {
                return;
            }
        }

        final ComputerLauncher nodeLauncher = vmSlave.getLauncher();
        if (!(nodeLauncher instanceof VirtualMachineLauncher)) {
            return;
        }
        final VirtualMachineLauncher launcher = (VirtualMachineLauncher) nodeLauncher;
        if (launcher.isLaunchSupported()
                && launcher.effectiveRevertPolicy() == VirtualMachineLauncher.RevertPolicy.AFTER_JOB) {
            revertAfterJob(launcher);
        }
    }

    /**
     * Implements the revert policy "After every job": the agent is disconnected, the virtual machine
     * rolled back and the agent relaunched, so the next build finds a clean machine.
     */
    private void revertAfterJob(VirtualMachineLauncher launcher) {
        if (!isRevertingSnapshot.compareAndSet(false, true)) {
            getListener().getLogger().println("INFO: Snapshot revert already in progress, skipping the revert after the job");
            return;
        }
        revertingThread = Thread.currentThread();
        try {
            disconnect(OfflineCause.create(Messages._VirtualMachineSlaveComputer_disconnectBeforeSnapshotRevert()))
                    .get();
            getListener().getLogger().println("INFO: agent disconnected");

            launcher.revertSnapshot(this, getListener());
            getListener().getLogger().println("INFO: snapshot reverted after the job");

            // Go through connect() so Jenkins knows a launch is in progress (isConnecting).
            connect(true).get();
            getListener().getLogger().println("INFO: agent launched");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            getListener().getLogger().println("ERROR: Interrupted while reverting the snapshot after the job");
        } catch (ExecutionException e) {
            getListener().getLogger().println("ERROR: Snapshot revert after the job failed: " + causeMessage(e));
            LOGGER.log(Level.WARNING, "Reverting " + getName() + " after the job failed", e);
        } catch (Exception e) {
            getListener().getLogger().println("ERROR: Snapshot revert after the job failed: " + e.getMessage());
            LOGGER.log(Level.WARNING, "Reverting " + getName() + " after the job failed", e);
        } finally {
            revertingThread = null;
            isRevertingSnapshot.set(false);
        }
    }

    private static String causeMessage(ExecutionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    /**
     * Fails the build that was just accepted, so it does not run on a virtual machine
     * that was not reverted to the configured snapshot.
     */
    private void abort(Executor executor, String message, Exception e) {
        getListener().getLogger().println("ERROR: " + message);
        if (e != null) {
            LOGGER.log(Level.WARNING, "Reverting " + getName() + " before the job failed", e);
        } else {
            LOGGER.log(Level.WARNING, "Reverting {0} before the job failed: {1}", new Object[] {getName(), message});
        }
        if (executor != null) {
            executor.interrupt(Result.FAILURE, new SnapshotRevertFailed(message));
        }
    }

    /** Explains in the build log why the build was aborted. */
    static final class SnapshotRevertFailed extends CauseOfInterruption {

        private static final long serialVersionUID = 1L;

        private final String message;

        SnapshotRevertFailed(String message) {
            this.message = message;
        }

        @Override
        public String getShortDescription() {
            return "Proxmox: " + message;
        }
    }

    /** {@link TrackedItem} implementation for the cloud statistics plugin. */
    @Override
    @CheckForNull
    public ProvisioningActivity.Id getId() {
        Node node = getNode();
        if (node instanceof VirtualMachineSlave) {
            return ((VirtualMachineSlave) node).getId();
        }
        // Computers that are not yet added to Jenkins have no node
        return slave != null ? slave.getId() : null;
    }

    // Details of the virtual machine for the status page

    public String getVmId() {
        VirtualMachineSlave node = getNode();
        return node != null ? String.valueOf(node.getVirtualMachineId()) : "Unknown";
    }

    public String getProxmoxNode() {
        VirtualMachineSlave node = getNode();
        return node != null ? node.getDatacenterNode() : "Unknown";
    }

    public String getDatacenterDescription() {
        VirtualMachineSlave node = getNode();
        return node != null ? node.getDatacenterDescription() : "Unknown";
    }

    public String getSnapshotName() {
        VirtualMachineSlave node = getNode();
        return node != null ? node.getSnapshotName() : "None";
    }

    public String getRevertPolicy() {
        VirtualMachineSlave node = getNode();
        if (node != null) {
            VirtualMachineLauncher.RevertPolicy policy = node.getRevertPolicy();
            return policy != null ? policy.toString() : "NEVER";
        }
        return "NEVER";
    }

    public boolean isStartVmEnabled() {
        VirtualMachineSlave node = getNode();
        return node != null && Boolean.TRUE.equals(node.getStartVM());
    }

    public int getStartupWaitTime() {
        VirtualMachineSlave node = getNode();
        return node != null ? node.getStartupWaitingPeriodSeconds() : 0;
    }

    public Integer getLimitedBuildsCount() {
        VirtualMachineSlave node = getNode();
        return node != null ? node.getLimitedBuildsCount() : null;
    }

    public Integer getBuildsExecuted() {
        VirtualMachineSlave node = getNode();
        return node != null ? node.getBuildsExecuted() : null;
    }

    public boolean hasLimitedBuilds() {
        VirtualMachineSlave node = getNode();
        return node != null && node.getLimitedBuildsCount() > 0;
    }
}
