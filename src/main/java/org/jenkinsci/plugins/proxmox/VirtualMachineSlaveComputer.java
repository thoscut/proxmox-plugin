package org.jenkinsci.plugins.proxmox;

import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Result;
import hudson.model.Slave;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.CauseOfInterruption;

public class VirtualMachineSlaveComputer extends SlaveComputer {

    private static final Logger LOGGER = Logger.getLogger(VirtualMachineSlaveComputer.class.getName());

    private final AtomicBoolean isRevertingSnapshot = new AtomicBoolean(false);

    public VirtualMachineSlaveComputer(Slave slave) {
        super(slave);
    }

    @Override
    public void tryReconnect() {
        if (isRevertingSnapshot.get()) {
            getListener().getLogger().println("INFO: trying to reconnect while snapshot revert - ignoring");
            return;
        }

        super.tryReconnect();
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

        try {
            final Future<?> disconnectFuture = disconnect(
                    OfflineCause.create(Messages._VirtualMachineSlaveComputer_disconnectBeforeSnapshotRevert()));
            disconnectFuture.get();
            getListener().getLogger().println("INFO: agent disconnected");

            launcher.revertSnapshot(this, getListener());
            getListener().getLogger().println("INFO: snapshot reverted");

            // Go through connect() so Jenkins knows a launch is in progress (isConnecting) and
            // retention strategies do not start a second, overlapping launch.
            connect(true).get();
            if (getChannel() == null) {
                abort(executor, "Agent did not reconnect after the snapshot revert", null);
                return;
            }
            getListener().getLogger().println("INFO: agent launched");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort(executor, "Interrupted while reverting the snapshot", e);
        } catch (ExecutionException e) {
            abort(executor, "Exception while performing asynchronous disconnect: " + e.getMessage(), e);
        } catch (Exception e) {
            abort(executor, "Snapshot revert failed: " + e.getMessage(), e);
        } finally {
            isRevertingSnapshot.set(false);
        }
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
}
