package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Periodic monitor for Proxmox Cloud health and statistics.
 * Updates statistics every 5 minutes to track node health and performance.
 */
@Extension
public class ProxmoxCloudMonitor extends AsyncPeriodicWork {
    
    private static final Logger LOGGER = Logger.getLogger(ProxmoxCloudMonitor.class.getName());
    
    // Check every 5 minutes
    private static final long RECURRENCE_PERIOD_MS = TimeUnit.MINUTES.toMillis(5);
    
    public ProxmoxCloudMonitor() {
        super("Proxmox Cloud Monitor");
    }
    
    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_PERIOD_MS;
    }
    
    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        
        int totalDatacenters = 0;
        int healthyDatacenters = 0;
        int unhealthyDatacenters = 0;
        
        for (Cloud cloud : jenkins.clouds) {
            if (cloud instanceof Datacenter) {
                totalDatacenters++;
                Datacenter datacenter = (Datacenter) cloud;
                
                try {
                    // Update statistics and health information
                    datacenter.updateStatistics();
                    
                    ProxmoxCloudStatistics stats = datacenter.getStatistics();
                    if (stats.isDatacenterReachable()) {
                        healthyDatacenters++;
                        
                        // Log summary information
                        listener.getLogger().println(String.format(
                            "[Proxmox Monitor] %s: %d slaves (%d online, %d offline), %.1f%% success rate",
                            datacenter.getDatacenterDescription(),
                            stats.getCurrentSlaveCount(),
                            stats.getOnlineSlaves(),
                            stats.getOfflineSlaves(),
                            stats.getSuccessRate()
                        ));
                        
                        // Log warnings for high failure rates
                        if (stats.getTotalProvisioningAttempts() > 5 && stats.getSuccessRate() < 80) {
                            listener.getLogger().println(String.format(
                                "[Proxmox Monitor] WARNING: Low success rate for %s: %.1f%% (%d/%d successful)",
                                datacenter.getDatacenterDescription(),
                                stats.getSuccessRate(),
                                stats.getSuccessfulProvisionings(),
                                stats.getTotalProvisioningAttempts()
                            ));
                        }
                        
                        // Log node health warnings
                        for (ProxmoxCloudStatistics.NodeHealth nodeHealth : stats.getNodeHealthMap().values()) {
                            if (!nodeHealth.online) {
                                listener.getLogger().println(String.format(
                                    "[Proxmox Monitor] WARNING: Node %s is offline in datacenter %s",
                                    nodeHealth.nodeName,
                                    datacenter.getDatacenterDescription()
                                ));
                            } else if (nodeHealth.cpuUsage > 90 || nodeHealth.memoryUsage > 90 || nodeHealth.diskUsage > 90) {
                                listener.getLogger().println(String.format(
                                    "[Proxmox Monitor] WARNING: High resource usage on node %s in datacenter %s: CPU %.1f%%, Memory %.1f%%, Disk %.1f%%",
                                    nodeHealth.nodeName,
                                    datacenter.getDatacenterDescription(),
                                    nodeHealth.cpuUsage,
                                    nodeHealth.memoryUsage,
                                    nodeHealth.diskUsage
                                ));
                            }
                        }
                        
                    } else {
                        unhealthyDatacenters++;
                        listener.getLogger().println(String.format(
                            "[Proxmox Monitor] ERROR: Datacenter %s is unreachable: %s",
                            datacenter.getDatacenterDescription(),
                            stats.getLastErrorMessage()
                        ));
                    }
                    
                } catch (Exception e) {
                    unhealthyDatacenters++;
                    LOGGER.log(Level.WARNING, "Failed to monitor datacenter: " + datacenter.getDatacenterDescription(), e);
                    listener.getLogger().println(String.format(
                        "[Proxmox Monitor] ERROR: Failed to monitor datacenter %s: %s",
                        datacenter.getDatacenterDescription(),
                        e.getMessage()
                    ));
                }
            }
        }
        
        // Summary logging
        if (totalDatacenters > 0) {
            listener.getLogger().println(String.format(
                "[Proxmox Monitor] Health check completed: %d datacenters total, %d healthy, %d unhealthy",
                totalDatacenters,
                healthyDatacenters,
                unhealthyDatacenters
            ));
            
            if (unhealthyDatacenters > 0) {
                LOGGER.log(Level.WARNING, "Proxmox monitor found {0} unhealthy datacenters out of {1} total", 
                          new Object[]{unhealthyDatacenters, totalDatacenters});
            }
        }
    }
    
    @Override
    protected Level getNormalLoggingLevel() {
        return Level.FINE; // Reduce log noise for normal operations
    }
    
    @Override
    protected Level getSlowLoggingLevel() {
        return Level.INFO; // Log when monitoring takes longer than expected
    }
}