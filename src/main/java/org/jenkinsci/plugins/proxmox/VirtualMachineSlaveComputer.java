package org.jenkinsci.plugins.proxmox;

import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Node;
import hudson.model.Queue;
import hudson.model.Slave;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public class VirtualMachineSlaveComputer extends SlaveComputer {

    private AtomicBoolean isRevertingSnapshot = new AtomicBoolean(false);

    public VirtualMachineSlaveComputer(Slave slave) {
        super(slave);
    }

    @Override
    public void tryReconnect() {
        if (isRevertingSnapshot.get()) {
            getListener().getLogger().println("INFO: trying to reconnect while snapshot revert - ignoring");
            return;
        }

        // Don't try to reconnect if the node is permanently disabled
        if (!isAcceptingTasks()) {
            getListener().getLogger().println("INFO: Node is disabled, skipping reconnection attempt");
            return;
        }
        
        // Don't try to reconnect if manually taken offline (not temporarily offline)
        if (isOffline() && !isTemporarilyOffline()) {
            getListener().getLogger().println("INFO: Node is manually offline, skipping reconnection attempt");
            return;
        }

        super.tryReconnect();
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        final Node node = getNode();

        if (node instanceof VirtualMachineSlave) {
            final VirtualMachineSlave slave = (VirtualMachineSlave) node;
            final VirtualMachineLauncher launcher = (VirtualMachineLauncher) slave.getLauncher();

            if (launcher != null && launcher.isLaunchSupported()
                    && (slave.getRevertPolicy() == VirtualMachineLauncher.RevertPolicy.BEFORE_JOB)) {
                // For BEFORE_JOB, we need to revert the VM before executing the job
                // We do this by putting the executor into a waiting state while reverting
                performBeforeJobRevert(slave, launcher, executor, task);
                return; // Don't call super.taskAccepted yet
            }
        }

        super.taskAccepted(executor, task);
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        super.taskCompleted(executor, task, durationMS);

        final Node node = getNode();

        if (node instanceof VirtualMachineSlave) {
            final VirtualMachineSlave slave = (VirtualMachineSlave) node;
            final VirtualMachineLauncher launcher = (VirtualMachineLauncher) slave.getLauncher();

            if (launcher.isLaunchSupported()
                    && (slave.getRevertPolicy() == VirtualMachineLauncher.RevertPolicy.AFTER_JOB)) {
                performSnapshotRevert(slave, launcher, "after job");
            }
        }
    }

    private void performBeforeJobRevert(VirtualMachineSlave slave, VirtualMachineLauncher launcher, Executor executor, Queue.Task task) {
        if (isRevertingSnapshot.compareAndSet(false, true)) {
            try {
                getListener().getLogger().println("INFO: Starting BEFORE_JOB snapshot revert");
                
                // Perform the snapshot revert synchronously - this will block until complete
                launcher.revertSnapshot(this, getListener());
                getListener().getLogger().println("INFO: BEFORE_JOB snapshot revert completed successfully");
                
                // Wait for VM to stabilize after revert
                getListener().getLogger().println("INFO: Waiting for VM to stabilize and agent to reconnect...");
                Thread.sleep(3000);
                
                // Wait for agent to reconnect after VM restart
                waitForAgentReconnection();
                
                getListener().getLogger().println("INFO: Agent reconnected, proceeding with job execution");
                
            } catch (Exception e) {
                getListener().getLogger().println("ERROR: BEFORE_JOB snapshot revert failed: " + e.getMessage());
                e.printStackTrace();
                // Continue with job execution even if revert fails
            } finally {
                isRevertingSnapshot.set(false);
            }
        } else {
            getListener().getLogger().println("INFO: Snapshot revert already in progress, waiting...");
            // Wait for the other revert to complete
            while (isRevertingSnapshot.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            getListener().getLogger().println("INFO: Snapshot revert completed, proceeding with job");
        }
        
        // Always proceed with job execution after revert is complete
        super.taskAccepted(executor, task);
    }

    private void waitForAgentReconnection() throws InterruptedException {
        getListener().getLogger().println("INFO: Waiting for agent to reconnect after VM restart...");
        
        // Close the old channel to allow the agent to reconnect properly
        hudson.remoting.Channel oldChannel = getChannel();
        if (oldChannel != null) {
            getListener().getLogger().println("INFO: Closing old channel to allow fresh reconnection...");
            getListener().getLogger().println("INFO: (Note: Connection terminated error below is expected and normal during VM restart)");
            try {
                oldChannel.close();
                getListener().getLogger().println("INFO: Old channel closed successfully");
            } catch (Throwable e) {
                // All exceptions during channel closing are expected when the VM is restarting
                getListener().getLogger().println("INFO: Old channel closure completed (expected connection termination)");
            }
        }
        
        // Use configured startup wait time from the slave
        int waitSeconds = 90; // Default fallback
        if (getNode() instanceof VirtualMachineSlave) {
            VirtualMachineSlave vmSlave = (VirtualMachineSlave) getNode();
            waitSeconds = vmSlave.getStartupWaitingPeriodSeconds();
        }
        getListener().getLogger().println("INFO: Waiting " + waitSeconds + " seconds for complete VM restart...");
        Thread.sleep(waitSeconds * 1000);
        
        // Now wait for agent connection to be established after restart
        int verificationSeconds = 120; // Up to 2 minutes for connection
        int checkInterval = 5; // Check every 5 seconds
        
        getListener().getLogger().println("INFO: Waiting for agent connection to be established...");
        for (int waited = 0; waited < verificationSeconds; waited += checkInterval) {
            hudson.remoting.Channel currentChannel = getChannel();
            
            // Check if we have a working channel after the restart
            if (isOnline() && currentChannel != null) {
                getListener().getLogger().println("INFO: Agent connection detected, testing stability...");
                
                // Test the new channel stability
                Thread.sleep(8000); // Wait 8 seconds
                
                // Re-check that we still have the same new channel and it's stable
                hudson.remoting.Channel verifyChannel = getChannel();
                if (isOnline() && verifyChannel != null && verifyChannel == currentChannel) {
                    getListener().getLogger().println("INFO: Agent connection verified as stable after " + (waitSeconds + waited + 8) + " seconds total");
                    
                    // Final extended stability check
                    Thread.sleep(5000);
                    if (isOnline() && getChannel() == currentChannel) {
                        getListener().getLogger().println("INFO: Final stability check passed, proceeding with job execution");
                        return;
                    } else {
                        getListener().getLogger().println("INFO: Final stability check failed, connection changed again");
                    }
                }
            }
            
            if (waited % 20 == 0 && waited > 0) {
                getListener().getLogger().println("INFO: Still waiting for new agent connection... (" + waited + "/" + verificationSeconds + " seconds)");
            }
            Thread.sleep(checkInterval * 1000);
        }
        
        // If we get here, verification failed
        getListener().getLogger().println("WARNING: Could not verify stable agent connection after " + (waitSeconds + verificationSeconds) + " seconds, proceeding anyway");
    }

    private void performSnapshotRevert(VirtualMachineSlave slave, VirtualMachineLauncher launcher, String timing) {
        if (isRevertingSnapshot.compareAndSet(false, true)) {
            try {
                getListener().getLogger().println("INFO: Starting snapshot revert " + timing);

                final Future<?> disconnectFuture = disconnect(OfflineCause.create(
                        Messages._VirtualMachineSlaveComputer_disconnectBeforeSnapshotRevert()));
                disconnectFuture.get();
                getListener().getLogger().println("INFO: agent disconnected");

                launcher.revertSnapshot(this, getListener());
                getListener().getLogger().println("INFO: snapshot reverted " + timing);

                launcher.launch(this, getListener());
                getListener().getLogger().println("INFO: agent launched");
            } catch (IOException | InterruptedException e) {
                getListener().getLogger().println("ERROR: Snapshot revert failed: " + e.getMessage());
            } catch (ExecutionException e) {
                getListener()
                        .getLogger()
                        .println("ERROR: Exception while performing asynchronous disconnect: " + e.getMessage());
            } finally {
                isRevertingSnapshot.set(false);
            }
        } else {
            getListener().getLogger().println("INFO: Snapshot revert already in progress, skipping " + timing + " revert");
        }
    }

    @Override
    protected Future<?> _connect(boolean forceReconnect) {
        return super._connect(forceReconnect);
    }
}
