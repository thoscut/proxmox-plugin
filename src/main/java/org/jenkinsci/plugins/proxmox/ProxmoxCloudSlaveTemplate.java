package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.NodeProperty;
import hudson.slaves.RetentionStrategy;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;

import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

public class ProxmoxCloudSlaveTemplate extends AbstractDescribableImpl<ProxmoxCloudSlaveTemplate> {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxCloudSlaveTemplate.class.getName());

    // Synchronization map to prevent concurrent clone operations from the same template VM
    private static final ConcurrentHashMap<String, Object> CLONE_LOCKS = new ConcurrentHashMap<>();
    
    private final String templateName;
    private final String labels;
    private final String remoteFS;
    private final String numExecutors;
    private final String datacenterNode;
    private final String templateVmId;
    private final String snapshotName;
    private final int instanceCap;
    private final int maxIdleMinutes;
    private final int limitedBuildsBeforeDisconnect;
    private final boolean startVM;
    private final boolean linkedClone;
    private final int startupWaitingPeriodSeconds;
    private final ComputerLauncher launcher;
    private final RetentionStrategy<?> retentionStrategy;
    private final List<? extends NodeProperty<?>> nodeProperties;
    private final String postCloneCommand;
    private final int postCloneCommandTimeout;
    private final boolean runPostCloneCommand;
    private boolean waitForGuestAgent;
    private int waitForGuestAgentTimeoutSeconds;

    private transient Set<LabelAtom> labelSet;
    private transient AtomicInteger currentlyProvisioning = new AtomicInteger(0);

    @DataBoundConstructor
    public ProxmoxCloudSlaveTemplate(String templateName,
                                   String labels,
                                   String remoteFS,
                                   String numExecutors,
                                   String datacenterNode,
                                   String templateVmId,
                                   String snapshotName,
                                   int instanceCap,
                                   int maxIdleMinutes,
                                   int limitedBuildsBeforeDisconnect,
                                   boolean startVM,
                                   boolean linkedClone,
                                   int startupWaitingPeriodSeconds,
                                   ComputerLauncher launcher,
                                   RetentionStrategy<?> retentionStrategy,
                                   List<? extends NodeProperty<?>> nodeProperties,
                                   String postCloneCommand,
                                   int postCloneCommandTimeout,
                                   boolean runPostCloneCommand) {
        this.templateName = templateName;
        this.labels = labels;
        this.remoteFS = remoteFS;
        this.numExecutors = numExecutors;
        this.datacenterNode = datacenterNode;
        this.templateVmId = templateVmId;
        this.snapshotName = snapshotName;
        this.instanceCap = instanceCap;
        this.maxIdleMinutes = maxIdleMinutes;
        this.limitedBuildsBeforeDisconnect = limitedBuildsBeforeDisconnect;
        this.startVM = startVM;
        this.linkedClone = linkedClone;
        this.startupWaitingPeriodSeconds = startupWaitingPeriodSeconds;
        this.launcher = launcher;
        this.retentionStrategy = retentionStrategy;
        this.nodeProperties = nodeProperties;
        this.postCloneCommand = postCloneCommand;
        this.postCloneCommandTimeout = postCloneCommandTimeout > 0 ? postCloneCommandTimeout : 300;
        this.runPostCloneCommand = runPostCloneCommand;
        // Default values for new fields (will be set via setters or readResolve)
        this.waitForGuestAgent = false;
        this.waitForGuestAgentTimeoutSeconds = 120;
    }

    @DataBoundSetter
    public void setWaitForGuestAgent(boolean waitForGuestAgent) {
        this.waitForGuestAgent = waitForGuestAgent;
    }

    @DataBoundSetter
    public void setWaitForGuestAgentTimeoutSeconds(int waitForGuestAgentTimeoutSeconds) {
        this.waitForGuestAgentTimeoutSeconds = waitForGuestAgentTimeoutSeconds > 0 ? waitForGuestAgentTimeoutSeconds : 120;
    }

    public boolean canProvision(Label label) {
        return label == null || getLabelAtoms().contains(label);
    }

    private Set<LabelAtom> getLabelAtoms() {
        if (labelSet == null) {
            labelSet = Label.parse(labels);
        }
        return labelSet;
    }

    public VirtualMachineSlave provision(Datacenter datacenter) throws Exception {
        // For backward compatibility - generate a name if not provided
        return provision(datacenter, null);
    }

    public VirtualMachineSlave provision(Datacenter datacenter, String plannedNodeName) throws Exception {
        int currentCount = getCurrentSlaveCount();
        int instanceCap = getInstanceCap();
        if (currentCount >= instanceCap) {
            LOGGER.log(Level.WARNING, "Instance cap reached for template {0}: {1}/{2} slaves running", 
                      new Object[]{templateName, currentCount, instanceCap});
            throw new IllegalStateException("Instance cap reached for template: " + templateName + 
                                          " (" + currentCount + "/" + instanceCap + " slaves running)");
        }
        
        LOGGER.log(Level.INFO, "Provisioning new slave for template {0}: {1}/{2} slaves currently running", 
                  new Object[]{templateName, currentCount, instanceCap});

        if (currentlyProvisioning == null) {
            currentlyProvisioning = new AtomicInteger(0);
        }
        currentlyProvisioning.incrementAndGet();
        try {
            Connector proxmoxApi = datacenter.proxmoxInstance();
            // Use the planned node name if provided, otherwise generate one
            String cloneName = (plannedNodeName != null) ? plannedNodeName : generateCloneName(proxmoxApi);
            Integer clonedVmId = cloneVmFromTemplate(proxmoxApi, cloneName);
            
            if (startVM) {
                startClonedVm(proxmoxApi, clonedVmId, cloneName);

                // Wait for guest agent if configured
                if (waitForGuestAgent) {
                    waitForGuestAgentReady(proxmoxApi, clonedVmId, cloneName);
                }
            }

            // Execute post-clone command if configured
            if (runPostCloneCommand && postCloneCommand != null && !postCloneCommand.trim().isEmpty()) {
                executePostCloneCommand(proxmoxApi, clonedVmId, cloneName);
            }

            // Prepare node properties, adding limited builds property if configured
            List<NodeProperty<?>> enhancedNodeProperties = new java.util.ArrayList<>();
            if (nodeProperties != null) {
                enhancedNodeProperties.addAll(nodeProperties);
            }

            // Add DisableDeferredWipeoutNodeProperty if limited builds is configured
            if (limitedBuildsBeforeDisconnect > 0) {
                try {
                    Class<?> disableWipeoutClass = Class.forName("hudson.model.DisableDeferredWipeoutNodeProperty");
                    NodeProperty<?> disableWipeoutProperty = (NodeProperty<?>) disableWipeoutClass.getDeclaredConstructor().newInstance();
                    enhancedNodeProperties.add(disableWipeoutProperty);
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "DisableDeferredWipeoutNodeProperty not available, skipping", e);
                }

                // Add limited builds property
                try {
                    Class<?> limitedBuildsClass = Class.forName("hudson.slaves.NodeProperty");
                    // Note: The actual implementation of limited builds counting is handled by the node itself
                    // We set a marker that will be checked during build execution
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Could not configure limited builds property", e);
                }
            }

            VirtualMachineSlave slave = new VirtualMachineSlave(
                cloneName,
                "Proxmox Cloud Slave from template " + templateName,
                remoteFS,
                numExecutors,
                Node.Mode.NORMAL,
                labels,
                launcher,
                retentionStrategy,
                enhancedNodeProperties,
                datacenter.getDatacenterDescription(),
                datacenterNode,
                clonedVmId,
                snapshotName,
                startVM,
                startupWaitingPeriodSeconds,
                RevertPolicy.NEVER
            );

            // Set limited builds counter if configured
            if (limitedBuildsBeforeDisconnect > 0) {
                slave.setLimitedBuildsCount(limitedBuildsBeforeDisconnect);
            }

            return slave;
        } finally {
            if (currentlyProvisioning != null) {
                currentlyProvisioning.decrementAndGet();
            }
        }
    }

    private String generateCloneName(Connector proxmoxApi) throws LoginException {
        // Get the actual template VM name from Proxmox using the template VM ID
        HashMap<String, Integer> machines = proxmoxApi.getQemuMachines(datacenterNode);
        Integer templateVmIdInt = Integer.parseInt(templateVmId);
        
        String actualTemplateName = null;
        for (Map.Entry<String, Integer> entry : machines.entrySet()) {
            if (entry.getValue().equals(templateVmIdInt)) {
                actualTemplateName = entry.getKey();
                break;
            }
        }
        
        // Fallback to configured template name if not found
        if (actualTemplateName == null) {
            LOGGER.log(Level.WARNING, "Could not find template VM with ID {0}, using configured template name: {1}",
                      new Object[]{templateVmId.toString(), templateName});
            actualTemplateName = templateName;
        }
        
        return actualTemplateName + "-" + System.currentTimeMillis();
    }

    private Integer cloneVmFromTemplate(Connector proxmoxApi, String cloneName) throws LoginException {
        // Create a unique lock key based on datacenter node and template VM ID to prevent concurrent clones
        String lockKey = datacenterNode + ":" + templateVmId;
        Object lock = CLONE_LOCKS.computeIfAbsent(lockKey, k -> new Object());

        LOGGER.log(Level.INFO, "Acquiring clone lock for template VM {0} on node {1} (key: {2})",
                  new Object[]{templateVmId, datacenterNode, lockKey});

        synchronized (lock) {
            LOGGER.log(Level.INFO, "Clone lock acquired for template VM {0} on node {1}, starting clone operation",
                      new Object[]{templateVmId, datacenterNode});
            try {
                return performClone(proxmoxApi, cloneName, lockKey);
            } finally {
                LOGGER.log(Level.INFO, "Clone operation completed for template VM {0} on node {1}, releasing lock",
                          new Object[]{templateVmId, datacenterNode});
                // Clean up the lock if no other threads are waiting - this helps prevent memory leaks
                // We keep it simple and let the GC handle cleanup when the map grows too large
            }
        }
    }

    private Integer performClone(Connector proxmoxApi, String cloneName, String lockKey) throws LoginException {
        Integer templateVmIdInt = Integer.parseInt(templateVmId);
        
        // Get template name for better logging
        HashMap<String, Integer> machines = proxmoxApi.getQemuMachines(datacenterNode);
        String templateVmName = null;
        for (Map.Entry<String, Integer> entry : machines.entrySet()) {
            if (entry.getValue().equals(templateVmIdInt)) {
                templateVmName = entry.getKey();
                break;
            }
        }
        // Fallback if template name not found
        if (templateVmName == null) {
            templateVmName = "VM-" + templateVmIdInt;
        }
        
        // Determine the actual snapshot parameter to use first
        // This affects whether we can clone from a running VM
        String actualSnapshotParam = null;
        if (snapshotName != null && !snapshotName.isEmpty()) {
            if ("current".equals(snapshotName)) {
                // For "current", always clone from current state without snapshot parameter
                actualSnapshotParam = null;
                LOGGER.log(Level.FINE, "Cloning from current state of template VM {0} (ID: {1}) - no snapshot parameter used",
                          new Object[]{templateVmName, templateVmIdInt.toString()});
            } else {
                // Validate specific snapshot exists
                List<String> availableSnapshots = proxmoxApi.getQemuMachineSnapshots(datacenterNode, templateVmIdInt);
                if (!availableSnapshots.contains(snapshotName)) {
                    String errorMessage = "Snapshot '" + snapshotName + "' does not exist on template VM " + templateVmName + 
                                          " (ID: " + templateVmIdInt + "). Available snapshots: " + availableSnapshots + 
                                          " (Note: 'current' refers to the current state and is always available)";
                    LOGGER.log(Level.SEVERE, errorMessage);
                    throw new IllegalStateException(errorMessage);
                }
                actualSnapshotParam = snapshotName;
                LOGGER.log(Level.FINE, "Using snapshot '{0}' for template VM {1} (ID: {2})",
                          new Object[]{snapshotName, templateVmName, templateVmIdInt.toString()});
            }
        }

        // Check if template VM is running - only block if explicitly cloning from "current" state
        // Note: We allow cloning from running VMs when using a specific snapshot name
        boolean templateIsRunning = proxmoxApi.isQemuMachineRunning(datacenterNode, templateVmIdInt);
        boolean isCloningFromCurrentState = "current".equals(snapshotName) || (snapshotName == null || snapshotName.isEmpty());

        if (templateIsRunning && isCloningFromCurrentState && actualSnapshotParam == null) {
            String errorMessage = "Cannot clone from running template VM " + templateVmName + " (ID: " + templateVmIdInt +
                                  ") from current state. Template VM must be stopped before cloning from current state " +
                                  "to avoid data corruption. To clone from a running VM, select a specific snapshot instead.";
            LOGGER.log(Level.SEVERE, errorMessage);
            throw new IllegalStateException(errorMessage);
        }

        if (templateIsRunning) {
            if (actualSnapshotParam != null) {
                LOGGER.log(Level.INFO, "Template VM {0} (ID: {1}) is running, but cloning from snapshot '{2}' is safe",
                          new Object[]{templateVmName, templateVmIdInt.toString(), actualSnapshotParam});
            }
        } else {
            String stateInfo = actualSnapshotParam != null ? "from snapshot '" + actualSnapshotParam + "'" : "from current state";
            LOGGER.log(Level.FINE, "Template VM {0} (ID: {1}) is stopped, proceeding with clone operation {2}",
                      new Object[]{templateVmName, templateVmIdInt.toString(), stateInfo});
        }

        Integer nextVmId = getNextAvailableVmId(proxmoxApi);
        
        String taskStatus = null;
        boolean usedLinkedClone = false;
        
        // Try linked clone first if requested
        if (linkedClone) {
            try {
                taskStatus = proxmoxApi.cloneQemuMachine(datacenterNode, templateVmIdInt, nextVmId, cloneName, false, actualSnapshotParam);
                usedLinkedClone = true;
                String snapshotInfo = (actualSnapshotParam != null) ? " from snapshot '" + actualSnapshotParam + "'" : " from current state";
                LOGGER.log(Level.INFO, "Started linked clone task for VM {0} (ID: {1}) from template {2} (ID: {3}){4}: {5}",
                          new Object[]{cloneName, nextVmId.toString(), templateVmName, templateVmIdInt.toString(), snapshotInfo, taskStatus});
            } catch (RuntimeException e) {
                // Check if the error is due to linked clone not being supported
                if (e.getMessage() != null && e.getMessage().contains("Linked clone feature is not supported")) {
                    LOGGER.log(Level.WARNING, "Linked clone not supported for template {0} (ID: {1}), falling back to full clone: {2}",
                              new Object[]{templateVmName, templateVmIdInt.toString(), e.getMessage()});
                    // Will fall through to full clone attempt
                } else {
                    // Re-throw other types of runtime exceptions
                    throw e;
                }
            }
        }

        // If linked clone wasn't requested or failed, try full clone
        if (taskStatus == null) {
            taskStatus = proxmoxApi.cloneQemuMachine(datacenterNode, templateVmIdInt, nextVmId, cloneName, true, actualSnapshotParam);
            String snapshotInfo = (actualSnapshotParam != null) ? " from snapshot '" + actualSnapshotParam + "'" : " from current state";
            LOGGER.log(Level.INFO, "Started full clone task for VM {0} (ID: {1}) from template {2} (ID: {3}){4}: {5}",
                      new Object[]{cloneName, nextVmId.toString(), templateVmName, templateVmIdInt.toString(), snapshotInfo, taskStatus});
        }

        // Wait for clone task to complete before proceeding
        LOGGER.log(Level.INFO, "Waiting for clone task {0} to complete for VM {1}...",
                  new Object[]{taskStatus, cloneName});
        try {
            JSONObject taskResult = proxmoxApi.waitForTaskToFinish(datacenterNode, taskStatus);
            String finalStatus = taskResult.getString("status");
            String exitStatus = taskResult.has("exitstatus") ? taskResult.getString("exitstatus") : null;

            // Debug logging to understand the exact values
            LOGGER.log(Level.FINE, "DEBUG: Clone task result for VM {0} - finalStatus='{1}', exitStatus='{2}', has_exitstatus={3}",
                      new Object[]{cloneName, finalStatus, exitStatus, taskResult.has("exitstatus")});

            // Proxmox tasks can have status "stopped" with exitstatus "OK" for success
            // or status "OK" for immediate success
            boolean isSuccess = "OK".equals(finalStatus) ||
                               ("stopped".equals(finalStatus) && "OK".equals(exitStatus));

            LOGGER.log(Level.FINE, "DEBUG: Success evaluation for VM {0} - isSuccess={1}, condition1={2}, condition2={3}",
                      new Object[]{cloneName, isSuccess, "OK".equals(finalStatus),
                                  ("stopped".equals(finalStatus) && "OK".equals(exitStatus))});

            if (isSuccess) {
                LOGGER.log(Level.INFO, "Clone task completed successfully for VM {0} (ID: {1}) - status: {2}, exitstatus: {3}",
                          new Object[]{cloneName, nextVmId.toString(), finalStatus, exitStatus});
            } else {
                String errorMsg = "Clone task failed with status: " + finalStatus;
                if (exitStatus != null) {
                    errorMsg += ", exit status: " + exitStatus;
                }
                if (taskResult.has("errors") && !taskResult.isNull("errors")) {
                    errorMsg += ", errors: " + taskResult.getString("errors");
                }
                LOGGER.log(Level.SEVERE, "Clone task failed for VM {0}: {1}", new Object[]{cloneName, errorMsg});
                throw new RuntimeException("Clone failed for VM " + cloneName + ": " + errorMsg);
            }
        } catch (Exception e) {
            String errorMessage = "Clone task failed for VM " + cloneName + " (ID: " + nextVmId + "): " + e.getMessage();
            LOGGER.log(Level.SEVERE, errorMessage, e);
            throw new RuntimeException(errorMessage, e);
        }
        
        return nextVmId;
    }

    private Integer getNextAvailableVmId(Connector proxmoxApi) throws LoginException {
        // Use per-datacenter synchronization instead of global lock to prevent blocking other datacenters
        synchronized (this.datacenterNode.intern()) {
            // Use timestamp-based approach to reduce collisions
            long timestamp = System.currentTimeMillis();
            int baseId = 1000 + (int)(timestamp % 8000); // Spread IDs across 1000-9000 range

            // Get fresh VM list
            HashMap<String, Integer> existingVms = proxmoxApi.getQemuMachines(datacenterNode);

            // Start from timestamp-based ID and search for available ID
            int vmId = baseId;
            int attempts = 0;

            while (existingVms.containsValue(vmId) && attempts < 10000) {
                vmId++;
                attempts++;

                // Wrap around if we exceed reasonable range
                if (vmId > 99999) {
                    vmId = 1000;
                }

                // Avoid infinite loop
                if (vmId == baseId && attempts > 100) {
                    throw new RuntimeException("Unable to find available VM ID after " + attempts + " attempts on node " + datacenterNode);
                }
            }

            if (existingVms.containsValue(vmId)) {
                throw new RuntimeException("No available VM IDs found in range 1000-99999 on node " + datacenterNode);
            }

            LOGGER.log(Level.FINE, "Selected VM ID {0} for new clone on node {1} (base: {2}, attempts: {3})",
                      new Object[]{String.valueOf(vmId), datacenterNode, String.valueOf(baseId), String.valueOf(attempts)});
            return vmId;
        }
    }

    private void startClonedVm(Connector proxmoxApi, Integer vmId, String cloneName) throws LoginException {
        LOGGER.log(Level.INFO, "Starting cloned VM {0} (ID: {1}) - clone has completed successfully",
                  new Object[]{cloneName, vmId.toString()});

        String startTaskId = proxmoxApi.startQemuMachine(datacenterNode, vmId);
        LOGGER.log(Level.INFO, "VM start task initiated for {0} (ID: {1}): {2}",
                  new Object[]{cloneName, vmId.toString(), startTaskId});

        // Wait for VM start task to complete
        try {
            JSONObject startTaskResult = proxmoxApi.waitForTaskToFinish(datacenterNode, startTaskId);
            String startStatus = startTaskResult.getString("status");
            String startExitStatus = startTaskResult.has("exitstatus") ? startTaskResult.getString("exitstatus") : null;

            // Proxmox tasks can have status "stopped" with exitstatus "OK" for success
            // or status "OK" for immediate success
            boolean isSuccess = "OK".equals(startStatus) ||
                               ("stopped".equals(startStatus) && "OK".equals(startExitStatus));

            if (isSuccess) {
                LOGGER.log(Level.INFO, "VM {0} (ID: {1}) started successfully - status: {2}, exitstatus: {3}",
                          new Object[]{cloneName, vmId.toString(), startStatus, startExitStatus});
            } else {
                String errorMsg = "VM start task failed with status: " + startStatus;
                if (startExitStatus != null) {
                    errorMsg += ", exit status: " + startExitStatus;
                }
                if (startTaskResult.has("errors") && !startTaskResult.isNull("errors")) {
                    errorMsg += ", errors: " + startTaskResult.getString("errors");
                }
                LOGGER.log(Level.WARNING, "VM start failed for {0}: {1}", new Object[]{cloneName, errorMsg});
                throw new RuntimeException("VM start failed for " + cloneName + ": " + errorMsg);
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "VM start task failed for " + cloneName + ": " + e.getMessage(), e);
            throw new RuntimeException("VM start failed for " + cloneName, e);
        }

        if (startupWaitingPeriodSeconds > 0) {
            LOGGER.log(Level.INFO, "Waiting additional {0} seconds for VM {1} to fully boot...",
                      new Object[]{startupWaitingPeriodSeconds, cloneName});
            try {
                Thread.sleep(startupWaitingPeriodSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Interrupted while waiting for VM startup", e);
            }
        }
    }

    private int getCurrentSlaveCount() {
        int onlineCount = 0;
        int offlineCount = 0;
        int tempOfflineCount = 0;
        int totalMatchingNodes = 0;
        
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof VirtualMachineSlave) {
                VirtualMachineSlave vmSlave = (VirtualMachineSlave) node;
                if (templateName.equals(getTemplateNameFromSlave(vmSlave))) {
                    totalMatchingNodes++;
                    if (vmSlave.getComputer() != null) {
                        if (!vmSlave.getComputer().isOffline()) {
                            onlineCount++;
                        } else if (vmSlave.getComputer().isTemporarilyOffline()) {
                            tempOfflineCount++;
                        } else {
                            offlineCount++;
                        }
                    } else {
                        offlineCount++;
                    }
                }
            }
        }
        
        int currentlyProvisioningCount = (currentlyProvisioning != null ? currentlyProvisioning.get() : 0);
        int activeCount = onlineCount + tempOfflineCount + currentlyProvisioningCount;
        
        LOGGER.log(Level.FINE, "Slave count for template {0}: {1} online, {2} temp-offline, {3} offline, {4} provisioning = {5} active ({6} total nodes)", 
                  new Object[]{templateName, onlineCount, tempOfflineCount, offlineCount, currentlyProvisioningCount, activeCount, totalMatchingNodes});
        
        // Only count online, temporarily offline, and currently provisioning VMs
        return activeCount;
    }

    private String getTemplateNameFromSlave(VirtualMachineSlave slave) {
        String slaveName = slave.getNodeName();
        int dashIndex = slaveName.lastIndexOf('-');
        return dashIndex > 0 ? slaveName.substring(0, dashIndex) : slaveName;
    }

    private Object readResolve() {
        // Initialize transient fields after deserialization
        if (currentlyProvisioning == null) {
            currentlyProvisioning = new AtomicInteger(0);
        }
        if (labelSet == null) {
            // labelSet will be initialized lazily in getLabelAtoms()
        }
        // Initialize new fields with defaults for old configurations
        if (waitForGuestAgentTimeoutSeconds == 0) {
            waitForGuestAgentTimeoutSeconds = 120;
        }
        return this;
    }

    /**
     * Execute the post-clone command on the newly cloned VM using guest agent
     */
    private void executePostCloneCommand(Connector proxmoxApi, Integer vmId, String vmName) {
        LOGGER.log(Level.INFO, "Executing post-clone command on VM {0} (ID: {1})", new Object[]{vmName, vmId});
        LOGGER.log(Level.INFO, "Command: {0}", postCloneCommand);

        try {
            // Check if guest agent is available
            if (!proxmoxApi.isGuestAgentAvailable(datacenterNode, vmId)) {
                LOGGER.log(Level.WARNING, "Guest agent not available on VM {0}. Skipping post-clone command execution.", vmName);
                return;
            }

            // Execute the command
            String pid = proxmoxApi.executeGuestCommand(datacenterNode, vmId, postCloneCommand);
            LOGGER.log(Level.INFO, "Post-clone command started with PID: {0}", pid);

            // Wait for command completion
            LOGGER.log(Level.INFO, "Waiting for post-clone command completion (timeout: {0} seconds)", postCloneCommandTimeout);

            long startTime = System.currentTimeMillis();
            long timeoutMs = postCloneCommandTimeout * 1000L;
            JSONObject status = null;
            boolean completed = false;

            while (!completed && (System.currentTimeMillis() - startTime) < timeoutMs) {
                Thread.sleep(2000); // Check every 2 seconds

                status = proxmoxApi.getGuestCommandStatus(datacenterNode, vmId, pid);
                LOGGER.log(Level.INFO, "Command status response: {0}", status.toString());

                // Handle different formats for the 'exited' field
                if (status.has("exited")) {
                    Object exitedObj = status.get("exited");
                    boolean hasExited = false;

                    if (exitedObj instanceof Boolean) {
                        hasExited = (Boolean) exitedObj;
                    } else if (exitedObj instanceof Number) {
                        hasExited = ((Number) exitedObj).intValue() != 0;
                    } else if (exitedObj instanceof String) {
                        String exitedStr = (String) exitedObj;
                        hasExited = "true".equalsIgnoreCase(exitedStr) || "1".equals(exitedStr);
                    }

                    if (hasExited) {
                        completed = true;
                        LOGGER.log(Level.INFO, "Post-clone command completed on VM {0}", vmName);
                    } else {
                        LOGGER.log(Level.FINE, "Post-clone command still running on VM {0}...", vmName);
                    }
                } else {
                    LOGGER.log(Level.FINE, "Post-clone command status does not contain 'exited' field for VM {0}...", vmName);
                }
            }

            if (!completed) {
                LOGGER.log(Level.WARNING, "Post-clone command timed out after {0} seconds on VM {1}",
                          new Object[]{postCloneCommandTimeout, vmName});
                return;
            }

            // Process results
            int exitCode = status.optInt("exitcode", -1);
            String stdout = status.optString("out-data", "");
            String stderr = status.optString("err-data", "");

            LOGGER.log(Level.INFO, "Post-clone command completed on VM {0} with exit code: {1}",
                      new Object[]{vmName, exitCode});

            if (!stdout.isEmpty()) {
                LOGGER.log(Level.INFO, "Post-clone command STDOUT from VM {0}: {1}", new Object[]{vmName, stdout});
            }

            if (!stderr.isEmpty() && exitCode != 0) {
                LOGGER.log(Level.WARNING, "Post-clone command STDERR from VM {0}: {1}", new Object[]{vmName, stderr});
            }

            if (exitCode != 0) {
                LOGGER.log(Level.WARNING, "Post-clone command failed on VM {0} with exit code: {1}",
                          new Object[]{vmName, exitCode});
            } else {
                LOGGER.log(Level.INFO, "Post-clone command executed successfully on VM {0}", vmName);
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to execute post-clone command on VM " + vmName + ": " + e.getMessage(), e);
        }
    }

    /**
     * Wait for the QEMU guest agent to become ready on the VM.
     * Similar to vSphere's "Wait for VMTools" feature.
     */
    private void waitForGuestAgentReady(Connector proxmoxApi, Integer vmId, String vmName) {
        LOGGER.log(Level.INFO, "Waiting for QEMU guest agent to become ready on VM {0} (ID: {1})",
                  new Object[]{vmName, vmId});

        long startTime = System.currentTimeMillis();
        long timeoutMs = waitForGuestAgentTimeoutSeconds * 1000L;
        int pollIntervalMs = 5000; // Poll every 5 seconds

        try {
            while ((System.currentTimeMillis() - startTime) < timeoutMs) {
                try {
                    // Check if guest agent is available
                    boolean isAvailable = proxmoxApi.isGuestAgentAvailable(datacenterNode, vmId);

                    if (isAvailable) {
                        LOGGER.log(Level.INFO, "QEMU guest agent is ready on VM {0} (ID: {1})",
                                  new Object[]{vmName, vmId});
                        return;
                    }

                    LOGGER.log(Level.FINE, "QEMU guest agent not ready yet on VM {0}, waiting...", vmName);

                } catch (Exception e) {
                    // Log but continue polling - guest agent might not be responding yet
                    LOGGER.log(Level.FINE, "Error checking guest agent status on VM {0}: {1}",
                              new Object[]{vmName, e.getMessage()});
                }

                // Wait before next poll
                Thread.sleep(pollIntervalMs);
            }

            // Timeout reached
            LOGGER.log(Level.WARNING,
                      "QEMU guest agent did not become ready within {0} seconds on VM {1} (ID: {2}). Continuing anyway.",
                      new Object[]{waitForGuestAgentTimeoutSeconds, vmName, vmId});

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Interrupted while waiting for guest agent on VM " + vmName, e);
        }
    }

    public String getTemplateName() { return templateName; }
    public String getLabels() { return labels; }
    public String getRemoteFS() { return remoteFS; }
    public String getNumExecutors() { return numExecutors; }
    public String getDatacenterNode() { return datacenterNode; }
    public String getTemplateVmId() { return templateVmId; }
    public String getSnapshotName() { return snapshotName; }
    public int getInstanceCap() { return instanceCap; }
    public int getMaxIdleMinutes() { return maxIdleMinutes; }
    public int getLimitedBuildsBeforeDisconnect() { return limitedBuildsBeforeDisconnect; }
    public boolean getStartVM() { return startVM; }
    public boolean getLinkedClone() { return linkedClone; }
    public int getStartupWaitingPeriodSeconds() { return startupWaitingPeriodSeconds; }
    public ComputerLauncher getLauncher() { return launcher; }
    public RetentionStrategy<?> getRetentionStrategy() { return retentionStrategy; }
    public List<? extends NodeProperty<?>> getNodeProperties() { return nodeProperties; }
    public String getPostCloneCommand() { return postCloneCommand; }
    public int getPostCloneCommandTimeout() { return postCloneCommandTimeout; }
    public boolean getRunPostCloneCommand() { return runPostCloneCommand; }
    public boolean getWaitForGuestAgent() { return waitForGuestAgent; }
    public int getWaitForGuestAgentTimeoutSeconds() { return waitForGuestAgentTimeoutSeconds; }

    @Extension
    public static class DescriptorImpl extends Descriptor<ProxmoxCloudSlaveTemplate> {

        @Override
        public String getDisplayName() {
            return "Proxmox Cloud Slave Template";
        }

        public ListBoxModel doFillDatacenterNodeItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            
            for (Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof Datacenter) {
                    Datacenter datacenter = (Datacenter) cloud;
                    for (String node : datacenter.getNodes()) {
                        items.add(node);
                    }
                }
            }
            return items;
        }

        public ListBoxModel doFillTemplateVmIdItems(@QueryParameter String datacenterNode) {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            
            if (datacenterNode != null && !datacenterNode.isEmpty()) {
                for (Cloud cloud : Jenkins.get().clouds) {
                    if (cloud instanceof Datacenter) {
                        Datacenter datacenter = (Datacenter) cloud;
                        HashMap<String, Integer> machines = datacenter.getQemuMachines(datacenterNode);
                        for (HashMap.Entry<String, Integer> entry : machines.entrySet()) {
                            items.add(entry.getKey(), entry.getValue().toString());
                        }
                    }
                }
            }
            return items;
        }

        public ListBoxModel doFillSnapshotNameItems(@QueryParameter String datacenterNode, 
                                                   @QueryParameter String templateVmId) {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            
            if (datacenterNode != null && !datacenterNode.isEmpty() && 
                templateVmId != null && !templateVmId.isEmpty()) {
                try {
                    Integer vmId = Integer.parseInt(templateVmId);
                    for (Cloud cloud : Jenkins.get().clouds) {
                        if (cloud instanceof Datacenter) {
                            Datacenter datacenter = (Datacenter) cloud;
                            List<String> snapshots = datacenter.getQemuMachineSnapshots(datacenterNode, vmId);
                            for (String snapshot : snapshots) {
                                items.add(snapshot);
                            }
                        }
                    }
                } catch (NumberFormatException e) {
                    // Invalid VM ID
                }
            }
            return items;
        }

        public FormValidation doCheckInstanceCap(@QueryParameter String value) {
            try {
                int cap = Integer.parseInt(value);
                if (cap < 0) {
                    return FormValidation.error("Instance cap must be non-negative");
                }
                if (cap > 100) {
                    return FormValidation.warning("Large instance cap may consume significant resources");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Instance cap must be a valid integer");
            }
        }

        public FormValidation doCheckMaxIdleMinutes(@QueryParameter String value) {
            try {
                int minutes = Integer.parseInt(value);
                if (minutes < 0) {
                    return FormValidation.error("Max idle minutes must be non-negative");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Max idle minutes must be a valid integer");
            }
        }

        @POST  
        public FormValidation doCheckTemplateVMStatus(
                @QueryParameter String datacenterNode,
                @QueryParameter String templateVmId) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            
            if (datacenterNode == null || datacenterNode.isEmpty()) {
                return FormValidation.error("Please select a datacenter node");
            }
            if (templateVmId == null || templateVmId.isEmpty()) {
                return FormValidation.error("Please select a template VM ID");
            }
            
            try {
                Integer vmId = Integer.parseInt(templateVmId);
                for (Cloud cloud : Jenkins.get().clouds) {
                    if (cloud instanceof Datacenter) {
                        Datacenter datacenter = (Datacenter) cloud;
                        Connector pveApi = datacenter.proxmoxInstance();
                        
                        boolean isRunning = pveApi.isQemuMachineRunning(datacenterNode, vmId);
                        JSONObject status = pveApi.getQemuMachineStatus(datacenterNode, vmId);
                        String vmStatus = status.getString("status");
                        String uptime = status.optString("uptime", "N/A");
                        
                        StringBuilder statusMessage = new StringBuilder();
                        statusMessage.append("Template VM ").append(vmId).append(" Status: ").append(vmStatus);
                        if (isRunning) {
                            statusMessage.append(" (Running)");
                            if (!uptime.equals("N/A")) {
                                statusMessage.append(", Uptime: ").append(uptime).append("s");
                            }
                        } else {
                            statusMessage.append(" (Stopped)");
                        }
                        
                        return FormValidation.ok(statusMessage.toString());
                    }
                }
                return FormValidation.error("No Proxmox datacenter found");
            } catch (NumberFormatException e) {
                return FormValidation.error("Invalid VM ID format");
            } catch (LoginException e) {
                return FormValidation.error("Login Failed: " + e.getMessage());
            } catch (Exception e) {
                return FormValidation.error("Status Check Failed: " + e.getMessage());
            }
        }
        
        public List<Descriptor<RetentionStrategy<?>>> getRetentionStrategyDescriptors() {
            return Jenkins.get().getDescriptorList(RetentionStrategy.class);
        }
    }
}