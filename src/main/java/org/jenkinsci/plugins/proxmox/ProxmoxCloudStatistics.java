package org.jenkinsci.plugins.proxmox;

import hudson.model.Computer;
import hudson.model.Node;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;

import javax.security.auth.login.LoginException;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Comprehensive statistics and health monitoring for Proxmox Cloud.
 * Tracks provisioning metrics, resource usage, and cluster health.
 */
public class ProxmoxCloudStatistics implements Serializable {
    
    private static final Logger LOGGER = Logger.getLogger(ProxmoxCloudStatistics.class.getName());
    private static final long serialVersionUID = 1L;
    
    // Singleton pattern for statistics collection
    private static final Map<String, ProxmoxCloudStatistics> STATISTICS = new ConcurrentHashMap<>();
    
    private final String datacenterDescription;
    private final Datacenter datacenter;
    
    // Provisioning Statistics
    private long totalProvisioningAttempts = 0;
    private long successfulProvisionings = 0;
    private long failedProvisionings = 0;
    private long averageProvisioningTimeMs = 0;
    private long lastProvisioningTimeMs = 0;
    private String lastProvisioningResult = "None";
    private LocalDateTime lastProvisioningTime = null;
    
    // Current State
    private int currentSlaveCount = 0;
    private int onlineSlaves = 0;
    private int offlineSlaves = 0;
    private int temporarilyOfflineSlaves = 0;
    private int provisioningSlaves = 0;
    
    // Node Health
    private Map<String, NodeHealth> nodeHealthMap = new ConcurrentHashMap<>();
    private boolean datacenterReachable = true;
    private String lastErrorMessage = "";
    private LocalDateTime lastHealthCheck = null;
    
    // Resource Usage
    private Map<String, ResourceUsage> resourceUsageMap = new ConcurrentHashMap<>();
    
    public static class NodeHealth implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String nodeName;
        public final boolean online;
        public final String status;
        public final double cpuUsage;
        public final double memoryUsage;
        public final double diskUsage;
        public final int runningVMs;
        public final LocalDateTime lastChecked;
        
        public NodeHealth(String nodeName, boolean online, String status, double cpuUsage, 
                         double memoryUsage, double diskUsage, int runningVMs) {
            this.nodeName = nodeName;
            this.online = online;
            this.status = status;
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
            this.diskUsage = diskUsage;
            this.runningVMs = runningVMs;
            this.lastChecked = LocalDateTime.now();
        }
        
        public String getHealthSummary() {
            if (!online) {
                return "❌ OFFLINE";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("✅ ONLINE - ");
            sb.append(String.format("CPU: %.1f%%, RAM: %.1f%%, Disk: %.1f%%, VMs: %d", 
                     cpuUsage, memoryUsage, diskUsage, runningVMs));
            return sb.toString();
        }
    }
    
    public static class ResourceUsage implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String nodeName;
        public final long totalMemory;
        public final long usedMemory;
        public final long totalDisk;
        public final long usedDisk;
        public final int totalCpuCores;
        public final LocalDateTime timestamp;
        
        public ResourceUsage(String nodeName, long totalMemory, long usedMemory, 
                           long totalDisk, long usedDisk, int totalCpuCores) {
            this.nodeName = nodeName;
            this.totalMemory = totalMemory;
            this.usedMemory = usedMemory;
            this.totalDisk = totalDisk;
            this.usedDisk = usedDisk;
            this.totalCpuCores = totalCpuCores;
            this.timestamp = LocalDateTime.now();
        }
        
        public double getMemoryUsagePercent() {
            return totalMemory > 0 ? (usedMemory * 100.0) / totalMemory : 0;
        }
        
        public double getDiskUsagePercent() {
            return totalDisk > 0 ? (usedDisk * 100.0) / totalDisk : 0;
        }
    }
    
    public ProxmoxCloudStatistics(Datacenter datacenter) {
        this.datacenter = datacenter;
        this.datacenterDescription = datacenter.getDatacenterDescription();
    }
    
    public static ProxmoxCloudStatistics getInstance(Datacenter datacenter) {
        return STATISTICS.computeIfAbsent(datacenter.getDatacenterDescription(), 
                k -> new ProxmoxCloudStatistics(datacenter));
    }
    
    public void recordProvisioningAttempt() {
        totalProvisioningAttempts++;
        lastProvisioningTime = LocalDateTime.now();
    }
    
    public void recordProvisioningSuccess(long durationMs) {
        successfulProvisionings++;
        lastProvisioningTimeMs = durationMs;
        averageProvisioningTimeMs = (averageProvisioningTimeMs + durationMs) / 2;
        lastProvisioningResult = "Success";
        LOGGER.log(Level.INFO, "Provisioning successful for {0} in {1}ms", 
                  new Object[]{datacenterDescription, durationMs});
    }
    
    public void recordProvisioningFailure(String error) {
        failedProvisionings++;
        lastProvisioningResult = "Failed: " + error;
        lastErrorMessage = error;
        LOGGER.log(Level.WARNING, "Provisioning failed for {0}: {1}", 
                  new Object[]{datacenterDescription, error});
    }
    
    public void updateCurrentStatus() {
        try {
            updateSlaveCounters();
            updateHealthStatus();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to update statistics for " + datacenterDescription, e);
        }
    }
    
    private void updateSlaveCounters() {
        int online = 0, offline = 0, tempOffline = 0, provisioning = 0;
        int total = 0;
        
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof VirtualMachineSlave) {
                VirtualMachineSlave vmSlave = (VirtualMachineSlave) node;
                if (datacenterDescription.equals(vmSlave.getDatacenterDescription())) {
                    total++;
                    Computer computer = node.toComputer();
                    if (computer != null) {
                        if (computer.isOnline()) {
                            online++;
                        } else if (computer.isTemporarilyOffline()) {
                            tempOffline++;
                        } else {
                            offline++;
                        }
                        
                        // Check if currently provisioning (connecting)
                        if (computer.isConnecting()) {
                            provisioning++;
                        }
                    }
                }
            }
        }
        
        currentSlaveCount = total;
        onlineSlaves = online;
        offlineSlaves = offline;
        temporarilyOfflineSlaves = tempOffline;
        provisioningSlaves = provisioning;
    }
    
    private void updateHealthStatus() {
        try {
            Connector proxmoxApi = datacenter.proxmoxInstance();
            List<String> nodes = proxmoxApi.getNodes();
            datacenterReachable = true;
            
            for (String nodeName : nodes) {
                try {
                    updateNodeHealth(proxmoxApi, nodeName);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to update health for node " + nodeName, e);
                    nodeHealthMap.put(nodeName, new NodeHealth(nodeName, false, "Error: " + e.getMessage(), 
                                    0, 0, 0, 0));
                }
            }
            
            lastHealthCheck = LocalDateTime.now();
            lastErrorMessage = "";
            
        } catch (LoginException | RuntimeException e) {
            datacenterReachable = false;
            lastErrorMessage = "Connection failed: " + e.getMessage();
            LOGGER.log(Level.SEVERE, "Failed to check health for datacenter " + datacenterDescription, e);
        }
    }
    
    private void updateNodeHealth(Connector proxmoxApi, String nodeName) throws LoginException {
        JSONObject nodeStatus = proxmoxApi.getNodeStatus(nodeName);
        List<JSONObject> vmDetails = proxmoxApi.getQemuMachineDetails(nodeName);
        
        String status = nodeStatus.optString("status", "unknown");
        boolean online = "online".equals(status);
        
        // Calculate resource usage
        double cpuUsage = nodeStatus.optDouble("cpu", 0) * 100;
        
        long maxMem = nodeStatus.optLong("maxmem", 1);
        long usedMem = nodeStatus.optLong("mem", 0);
        double memoryUsage = (usedMem * 100.0) / maxMem;
        
        long maxDisk = nodeStatus.optLong("maxdisk", 1);
        long usedDisk = nodeStatus.optLong("disk", 0);
        double diskUsage = (usedDisk * 100.0) / maxDisk;
        
        int runningVMs = 0;
        for (JSONObject vm : vmDetails) {
            if ("running".equals(vm.optString("status"))) {
                runningVMs++;
            }
        }
        
        NodeHealth health = new NodeHealth(nodeName, online, status, cpuUsage, memoryUsage, diskUsage, runningVMs);
        nodeHealthMap.put(nodeName, health);
        
        // Store resource usage details
        ResourceUsage usage = new ResourceUsage(nodeName, maxMem, usedMem, maxDisk, usedDisk, 
                                              nodeStatus.optInt("maxcpu", 1));
        resourceUsageMap.put(nodeName, usage);
    }
    
    // Getters for statistics
    public double getSuccessRate() {
        return totalProvisioningAttempts > 0 ? 
               (successfulProvisionings * 100.0) / totalProvisioningAttempts : 0;
    }
    
    public String getHealthSummary() {
        if (!datacenterReachable) {
            return "❌ DATACENTER UNREACHABLE - " + lastErrorMessage;
        }
        
        int onlineNodes = (int) nodeHealthMap.values().stream().mapToLong(h -> h.online ? 1 : 0).sum();
        int totalNodes = nodeHealthMap.size();
        
        if (totalNodes == 0) {
            return "⚠️ NO NODE DATA";
        }
        
        return String.format("✅ %d/%d nodes online - %d slaves (%d online, %d offline)", 
                           onlineNodes, totalNodes, currentSlaveCount, onlineSlaves, offlineSlaves);
    }
    
    public String getDetailedReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("=== PROXMOX CLOUD STATISTICS ===\n");
        report.append("Datacenter: ").append(datacenterDescription).append("\n");
        report.append("Last Updated: ").append(lastHealthCheck != null ? 
                     lastHealthCheck.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) : "Never").append("\n\n");
        
        // Provisioning Statistics
        report.append("--- PROVISIONING STATISTICS ---\n");
        report.append("Total Attempts: ").append(totalProvisioningAttempts).append("\n");
        report.append("Successful: ").append(successfulProvisionings).append("\n");
        report.append("Failed: ").append(failedProvisionings).append("\n");
        report.append("Success Rate: ").append(String.format("%.1f%%", getSuccessRate())).append("\n");
        report.append("Average Time: ").append(averageProvisioningTimeMs).append("ms\n");
        report.append("Last Result: ").append(lastProvisioningResult).append("\n\n");
        
        // Current Status
        report.append("--- CURRENT STATUS ---\n");
        report.append("Total Slaves: ").append(currentSlaveCount).append("\n");
        report.append("Online: ").append(onlineSlaves).append("\n");
        report.append("Offline: ").append(offlineSlaves).append("\n");
        report.append("Temporarily Offline: ").append(temporarilyOfflineSlaves).append("\n");
        report.append("Provisioning: ").append(provisioningSlaves).append("\n");
        report.append("Instance Cap: ").append(datacenter.getInstanceCap()).append("\n");
        report.append("Available Capacity: ").append(Math.max(0, datacenter.getInstanceCap() - currentSlaveCount)).append("\n\n");
        
        // Node Health
        report.append("--- NODE HEALTH ---\n");
        if (!datacenterReachable) {
            report.append("❌ DATACENTER UNREACHABLE: ").append(lastErrorMessage).append("\n");
        } else {
            for (NodeHealth health : nodeHealthMap.values()) {
                report.append(health.nodeName).append(": ").append(health.getHealthSummary()).append("\n");
            }
        }
        
        return report.toString();
    }
    
    // Getters
    public long getTotalProvisioningAttempts() { return totalProvisioningAttempts; }
    public long getSuccessfulProvisionings() { return successfulProvisionings; }
    public long getFailedProvisionings() { return failedProvisionings; }
    public long getAverageProvisioningTimeMs() { return averageProvisioningTimeMs; }
    public int getCurrentSlaveCount() { return currentSlaveCount; }
    public int getOnlineSlaves() { return onlineSlaves; }
    public int getOfflineSlaves() { return offlineSlaves; }
    public int getTemporarilyOfflineSlaves() { return temporarilyOfflineSlaves; }
    public int getProvisioningSlaves() { return provisioningSlaves; }
    public boolean isDatacenterReachable() { return datacenterReachable; }
    public String getLastErrorMessage() { return lastErrorMessage; }
    public Map<String, NodeHealth> getNodeHealthMap() { return Collections.unmodifiableMap(nodeHealthMap); }
    public Map<String, ResourceUsage> getResourceUsageMap() { return Collections.unmodifiableMap(resourceUsageMap); }
}