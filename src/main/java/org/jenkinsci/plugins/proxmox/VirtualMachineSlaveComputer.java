package org.jenkinsci.plugins.proxmox;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Slave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.util.Futures;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;

/**
 * The computer of a {@link VirtualMachineSlave}. It implements the revert policies that roll the
 * virtual machine back around every build.
 */
public class VirtualMachineSlaveComputer extends SlaveComputer {

    private static final Logger LOGGER = Logger.getLogger(VirtualMachineSlaveComputer.class.getName());

    private final AtomicBoolean isRevertingSnapshot = new AtomicBoolean(false);

    /** The executor thread that is reverting the snapshot; only it may relaunch the agent meanwhile. */
    private volatile Thread revertingThread;

    public VirtualMachineSlaveComputer(VirtualMachineSlave slave) {
        super(slave);
    }

    @Override
    @CheckForNull
    public VirtualMachineSlave getNode() {
        Slave node = super.getNode();
        return node instanceof VirtualMachineSlave ? (VirtualMachineSlave) node : null;
    }

    /**
     * Agents that were disabled or taken offline by a user stay offline; they are not relaunched
     * (which would revert and start the virtual machine) until the user brings them back.
     */
    @Override
    public void tryReconnect() {
        String reason = reasonToStayOffline(this);
        if (reason != null) {
            getListener().getLogger().println("INFO: " + reason + ", skipping reconnection attempt");
            return;
        }
        super.tryReconnect();
    }

    /** @return why a user wants the agent to stay offline, or {@code null} if it may be relaunched. */
    @CheckForNull
    static String reasonToStayOffline(SlaveComputer computer) {
        if (!computer.isAcceptingTasks()) {
            return "Node is disabled";
        }
        if (computer.isOffline() && computer.getOfflineCause() instanceof OfflineCause.UserCause) {
            return "Node is manually offline";
        }
        return null;
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

        final VirtualMachineSlave slave = getNode();
        if (slave == null) {
            return;
        }
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
        final VirtualMachineSlave vmSlave = getNode();
        if (vmSlave == null) {
            return;
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
}
