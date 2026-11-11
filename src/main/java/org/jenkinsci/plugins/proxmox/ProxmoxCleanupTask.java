package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.PeriodicWork;
import hudson.slaves.Cloud;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Periodic background task that cleans up orphaned Proxmox VM nodes.
 * This task runs periodically to ensure Jenkins nodes that no longer have
 * corresponding VMs in Proxmox are removed from Jenkins.
 *
 * This task is conservative and safe for Pipeline resumability:
 * - Only removes nodes where the VM has been deleted in Proxmox
 * - Never removes nodes with active or potentially resumable builds
 * - Respects the hasPotentiallyResumableBuilds() safety checks
 */
@Extension
public class ProxmoxCleanupTask extends PeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxCleanupTask.class.getName());

    /**
     * Run cleanup every hour (conservative interval to avoid overhead).
     */
    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.HOURS.toMillis(1);
    }

    @Override
    protected void doRun() throws Exception {
        LOGGER.log(Level.FINE, "Running periodic Proxmox VM cleanup");

        int totalClouds = 0;

        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof Datacenter) {
                Datacenter datacenter = (Datacenter) cloud;
                totalClouds++;

                try {
                    LOGGER.log(Level.FINE, "Checking datacenter for orphaned nodes: {0}",
                              datacenter.getDatacenterDescription());

                    // This will only cleanup nodes where the VM doesn't exist in Proxmox
                    // and there are no potentially resumable builds
                    datacenter.cleanupOrphanedNodes();

                } catch (Exception e) {
                    LOGGER.log(Level.WARNING,
                              "Periodic cleanup failed for datacenter: " +
                              datacenter.getDatacenterDescription(), e);
                }
            }
        }

        if (totalClouds > 0) {
            LOGGER.log(Level.FINE, "Periodic cleanup completed for {0} Proxmox datacenters", totalClouds);
        }
    }
}
