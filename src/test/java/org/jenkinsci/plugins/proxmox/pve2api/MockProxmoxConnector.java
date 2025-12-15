package org.jenkinsci.plugins.proxmox.pve2api;

import hudson.util.Secret;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;

import javax.security.auth.login.LoginException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock implementation of Proxmox Connector for testing.
 * Simulates Proxmox VE API behavior without requiring a real Proxmox server.
 */
public class MockProxmoxConnector extends Connector {

    private final Map<String, MockNode> nodes = new ConcurrentHashMap<>();
    private final Map<String, MockVM> vms = new ConcurrentHashMap<>();
    private final Map<String, MockTask> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger taskIdCounter = new AtomicInteger(1000);
    private final AtomicInteger vmIdCounter = new AtomicInteger(100);

    private boolean simulateLoginFailure = false;
    private boolean simulateTimeout = false;
    private int guestAgentDelayMs = 0;
    private boolean guestAgentAvailable = true;
    private Date mockAuthTimestamp = null;

    public MockProxmoxConnector(String hostname, String username, String realm, Secret password) {
        super(hostname, username, realm, password, true);
        initializeDefaultNodes();
    }

    public MockProxmoxConnector(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        super(hostname, username, realm, password, ignoreSSL);
        initializeDefaultNodes();
    }

    private void initializeDefaultNodes() {
        addNode("pve-node1", true, 50.0, 60.0); // 50% CPU, 60% memory
        addNode("pve-node2", true, 30.0, 40.0);
    }

    // Configuration methods for testing

    public void setSimulateLoginFailure(boolean simulateLoginFailure) {
        this.simulateLoginFailure = simulateLoginFailure;
    }

    public void setSimulateTimeout(boolean simulateTimeout) {
        this.simulateTimeout = simulateTimeout;
    }

    public void setGuestAgentDelayMs(int delayMs) {
        this.guestAgentDelayMs = delayMs;
    }

    public void setGuestAgentAvailable(boolean available) {
        this.guestAgentAvailable = available;
    }

    public MockNode addNode(String nodeName, boolean online, double cpuUsage, double memoryUsage) {
        MockNode node = new MockNode(nodeName, online, cpuUsage, memoryUsage);
        nodes.put(nodeName, node);
        return node;
    }

    public MockVM addVM(String node, int vmid, String name, String status) {
        MockVM vm = new MockVM(node, vmid, name, status);
        vms.put(vmKey(node, vmid), vm);
        return vm;
    }

    public MockVM getVM(String node, int vmid) {
        return vms.get(vmKey(node, vmid));
    }

    public void removeVM(String node, int vmid) {
        vms.remove(vmKey(node, vmid));
    }

    private String vmKey(String node, int vmid) {
        return node + ":" + vmid;
    }

    // Overridden Connector methods

    @Override
    public void login() throws LoginException {
        if (simulateLoginFailure) {
            throw new LoginException("Simulated login failure");
        }
        // Mock login - just set timestamp
        this.mockAuthTimestamp = new Date();
    }

    @Override
    public void checkIfAuthTicketIsValid() throws LoginException {
        if (mockAuthTimestamp == null) {
            login();
        }
    }

    @Override
    public List<String> getNodes() throws LoginException {
        checkIfAuthTicketIsValid();
        return new ArrayList<>(nodes.keySet());
    }

    @Override
    public JSONObject getNodeStatus(String node) throws LoginException {
        checkIfAuthTicketIsValid();
        MockNode mockNode = nodes.get(node);
        if (mockNode == null) {
            throw new RuntimeException("Node not found: " + node);
        }

        JSONObject status = new JSONObject();
        status.put("node", node);
        status.put("status", mockNode.isOnline() ? "online" : "offline");
        status.put("uptime", 86400);
        status.put("cpu", mockNode.getCpuUsage() / 100.0);
        status.put("maxcpu", 8);
        status.put("mem", (long)(mockNode.getMemoryUsage() / 100.0 * 16000000000L));
        status.put("maxmem", 16000000000L);
        return status;
    }

    @Override
    public JSONObject getClusterStatus() throws LoginException {
        checkIfAuthTicketIsValid();
        JSONObject cluster = new JSONObject();
        JSONArray dataArray = new JSONArray();

        JSONObject clusterInfo = new JSONObject();
        clusterInfo.put("type", "cluster");
        clusterInfo.put("name", "test-cluster");
        clusterInfo.put("quorate", 1);
        clusterInfo.put("nodes", nodes.size());
        dataArray.put(clusterInfo);

        cluster.put("data", dataArray);
        return cluster;
    }

    @Override
    public List<JSONObject> getQemuMachineDetails(String node) throws LoginException {
        checkIfAuthTicketIsValid();
        List<JSONObject> result = new ArrayList<>();

        for (MockVM vm : vms.values()) {
            if (vm.getNode().equals(node)) {
                JSONObject vmObj = new JSONObject();
                vmObj.put("vmid", vm.getVmid());
                vmObj.put("name", vm.getName());
                vmObj.put("status", vm.getStatus());
                vmObj.put("maxmem", vm.getMaxMem());
                vmObj.put("cpus", vm.getCpus());
                result.add(vmObj);
            }
        }
        return result;
    }

    @Override
    public HashMap<String, Integer> getQemuMachines(String node) throws LoginException {
        checkIfAuthTicketIsValid();
        HashMap<String, Integer> result = new HashMap<>();

        for (MockVM vm : vms.values()) {
            if (vm.getNode().equals(node)) {
                result.put(vm.getName(), vm.getVmid());
            }
        }
        return result;
    }

    @Override
    public JSONObject getQemuMachineStatus(String node, Integer vmid) throws LoginException {
        checkIfAuthTicketIsValid();
        MockVM vm = vms.get(vmKey(node, vmid));
        if (vm == null) {
            throw new RuntimeException("VM not found: " + vmid);
        }

        JSONObject status = new JSONObject();
        status.put("status", vm.getStatus());
        status.put("vmid", vm.getVmid());
        status.put("name", vm.getName());
        status.put("cpus", vm.getCpus());
        status.put("maxmem", vm.getMaxMem());
        status.put("uptime", vm.getStatus().equals("running") ? 3600 : 0);
        status.put("qmpstatus", vm.getStatus());
        return status;
    }

    @Override
    public Boolean isQemuMachineRunning(String node, Integer vmid) throws LoginException {
        checkIfAuthTicketIsValid();
        MockVM vm = vms.get(vmKey(node, vmid));
        return vm != null && "running".equals(vm.getStatus());
    }

    @Override
    public List<String> getQemuMachineSnapshots(String node, Integer vmid) throws LoginException {
        checkIfAuthTicketIsValid();
        MockVM vm = vms.get(vmKey(node, vmid));
        if (vm == null) {
            throw new RuntimeException("VM not found: " + vmid);
        }
        return new ArrayList<>(vm.getSnapshots().keySet());
    }

    @Override
    public String createQemuMachineSnapshot(String node, Integer vmid, String snapshotName) throws LoginException {
        return createQemuMachineSnapshot(node, vmid, snapshotName, null, true);
    }

    @Override
    public String createQemuMachineSnapshot(String node, Integer vmid, String snapshotName, String description, boolean includeRam) throws LoginException {
        checkIfAuthTicketIsValid();
        MockVM vm = vms.get(vmKey(node, vmid));
        if (vm == null) {
            throw new RuntimeException("VM not found: " + vmid);
        }

        vm.addSnapshot(snapshotName, description, includeRam);
        String taskId = "UPID:" + node + ":snapshot:" + taskIdCounter.incrementAndGet();
        tasks.put(taskId, new MockTask(taskId, "qmsnapshot", "stopped", "OK"));
        return taskId;
    }

    @Override
    public String deleteQemuMachineSnapshot(String node, Integer vmid, String snapshotName) throws LoginException {
        checkIfAuthTicketIsValid();
        MockVM vm = vms.get(vmKey(node, vmid));
        if (vm == null) {
            throw new RuntimeException("VM not found: " + vmid);
        }

        vm.removeSnapshot(snapshotName);
        String taskId = "UPID:" + node + ":delsnapshot:" + taskIdCounter.incrementAndGet();
        tasks.put(taskId, new MockTask(taskId, "qmdelsnapshot", "stopped", "OK"));
        return taskId;
    }

    @Override
    public String rollbackQemuMachineSnapshot(String node, Integer vmid, String snapshotName) throws LoginException {
        checkIfAuthTicketIsValid();
        MockVM vm = vms.get(vmKey(node, vmid));
        if (vm == null) {
            throw new RuntimeException("VM not found: " + vmid);
        }

        if (!vm.hasSnapshot(snapshotName)) {
            throw new RuntimeException("Snapshot not found: " + snapshotName);
        }

        // Simulate VM restart after rollback
        vm.setStatus("stopped");

        String taskId = "UPID:" + node + ":rollback:" + taskIdCounter.incrementAndGet();
        tasks.put(taskId, new MockTask(taskId, "qmrollback", "stopped", "OK"));
        return taskId;
    }

    @Override
    public String startQemuMachine(String node, Integer vmid) throws LoginException {
        checkIfAuthTicketIsValid();
        MockVM vm = vms.get(vmKey(node, vmid));
        if (vm == null) {
            throw new RuntimeException("VM not found: " + vmid);
        }

        vm.setStatus("running");
        String taskId = "UPID:" + node + ":start:" + taskIdCounter.incrementAndGet();
        tasks.put(taskId, new MockTask(taskId, "qmstart", "stopped", "OK"));
        return taskId;
    }

    @Override
    public String stopQemuMachine(String node, Integer vmid) throws LoginException {
        checkIfAuthTicketIsValid();
        MockVM vm = vms.get(vmKey(node, vmid));
        if (vm == null) {
            throw new RuntimeException("VM not found: " + vmid);
        }

        vm.setStatus("stopped");
        String taskId = "UPID:" + node + ":stop:" + taskIdCounter.incrementAndGet();
        tasks.put(taskId, new MockTask(taskId, "qmstop", "stopped", "OK"));
        return taskId;
    }

    @Override
    public String cloneQemuMachine(String node, Integer vmid, Integer newid, String name) throws LoginException {
        return cloneQemuMachine(node, vmid, newid, name, true, null);
    }

    @Override
    public String cloneQemuMachine(String node, Integer vmid, Integer newid, String name, boolean fullClone) throws LoginException {
        return cloneQemuMachine(node, vmid, newid, name, fullClone, null);
    }

    @Override
    public String cloneQemuMachine(String node, Integer vmid, Integer newid, String name, boolean fullClone, String snapname) throws LoginException {
        checkIfAuthTicketIsValid();
        MockVM sourceVM = vms.get(vmKey(node, vmid));
        if (sourceVM == null) {
            throw new RuntimeException("Source VM not found: " + vmid);
        }

        // Create cloned VM
        MockVM clonedVM = new MockVM(node, newid, name, "stopped");
        clonedVM.setCpus(sourceVM.getCpus());
        clonedVM.setMaxMem(sourceVM.getMaxMem());
        clonedVM.setTemplate(false);
        vms.put(vmKey(node, newid), clonedVM);

        String taskId = "UPID:" + node + ":clone:" + taskIdCounter.incrementAndGet();
        tasks.put(taskId, new MockTask(taskId, "qmclone", "stopped", "OK"));
        return taskId;
    }

    @Override
    public JSONObject getTaskStatus(String node, String taskId) throws LoginException {
        checkIfAuthTicketIsValid();

        if (simulateTimeout) {
            throw new RuntimeException("Simulated timeout");
        }

        MockTask task = tasks.get(taskId);
        if (task == null) {
            task = new MockTask(taskId, "unknown", "stopped", "OK");
        }

        JSONObject status = new JSONObject();
        status.put("status", task.getStatus());
        status.put("exitstatus", task.getExitStatus());
        status.put("upid", taskId);
        status.put("type", task.getType());
        return status;
    }

    @Override
    public JSONObject waitForTaskToFinish(String node, String taskId) throws LoginException, InterruptedException {
        checkIfAuthTicketIsValid();

        // Simulate some task execution time
        Thread.sleep(100);

        return getTaskStatus(node, taskId);
    }

    @Override
    public String executeGuestCommand(String node, Integer vmid, String command) throws LoginException {
        checkIfAuthTicketIsValid();

        if (!guestAgentAvailable) {
            throw new RuntimeException("QEMU guest agent is not running");
        }

        // Simulate guest agent delay
        if (guestAgentDelayMs > 0) {
            try {
                Thread.sleep(guestAgentDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        MockVM vm = vms.get(vmKey(node, vmid));
        if (vm == null) {
            throw new RuntimeException("VM not found: " + vmid);
        }

        if (!vm.getStatus().equals("running")) {
            throw new RuntimeException("VM is not running");
        }

        // Record the command execution
        vm.addExecutedCommand(command);

        // Return mock PID
        return String.valueOf(12345 + vm.getExecutedCommands().size());
    }

    // Additional helper method for testing guest exec status
    public JSONObject getGuestExecStatus(String node, Integer vmid, String pid) throws LoginException {
        checkIfAuthTicketIsValid();

        if (!guestAgentAvailable) {
            throw new RuntimeException("QEMU guest agent is not running");
        }

        JSONObject result = new JSONObject();
        JSONObject data = new JSONObject();
        data.put("exited", 1);
        data.put("exitcode", 0);
        data.put("out-data", "Command executed successfully");
        result.put("data", data);
        return result;
    }

    public int getNextVmId() {
        return vmIdCounter.incrementAndGet();
    }

    // Mock data classes

    public static class MockNode {
        private final String name;
        private boolean online;
        private double cpuUsage;
        private double memoryUsage;

        public MockNode(String name, boolean online, double cpuUsage, double memoryUsage) {
            this.name = name;
            this.online = online;
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
        }

        public String getName() { return name; }
        public boolean isOnline() { return online; }
        public void setOnline(boolean online) { this.online = online; }
        public double getCpuUsage() { return cpuUsage; }
        public void setCpuUsage(double cpuUsage) { this.cpuUsage = cpuUsage; }
        public double getMemoryUsage() { return memoryUsage; }
        public void setMemoryUsage(double memoryUsage) { this.memoryUsage = memoryUsage; }
    }

    public static class MockVM {
        private final String node;
        private final int vmid;
        private final String name;
        private String status;
        private int cpus = 2;
        private long maxMem = 2147483648L; // 2GB
        private boolean template = false;
        private final Map<String, MockSnapshot> snapshots = new ConcurrentHashMap<>();
        private final List<String> executedCommands = new ArrayList<>();

        public MockVM(String node, int vmid, String name, String status) {
            this.node = node;
            this.vmid = vmid;
            this.name = name;
            this.status = status;
        }

        public String getNode() { return node; }
        public int getVmid() { return vmid; }
        public String getName() { return name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public int getCpus() { return cpus; }
        public void setCpus(int cpus) { this.cpus = cpus; }
        public long getMaxMem() { return maxMem; }
        public void setMaxMem(long maxMem) { this.maxMem = maxMem; }
        public boolean isTemplate() { return template; }
        public void setTemplate(boolean template) { this.template = template; }

        public Map<String, MockSnapshot> getSnapshots() { return snapshots; }
        public boolean hasSnapshot(String name) { return snapshots.containsKey(name); }
        public void addSnapshot(String name, String description, boolean includeRam) {
            snapshots.put(name, new MockSnapshot(name, description, includeRam));
        }
        public void removeSnapshot(String name) { snapshots.remove(name); }

        public List<String> getExecutedCommands() { return executedCommands; }
        public void addExecutedCommand(String command) { executedCommands.add(command); }
    }

    public static class MockSnapshot {
        private final String name;
        private final String description;
        private final boolean includeRam;

        public MockSnapshot(String name, String description, boolean includeRam) {
            this.name = name;
            this.description = description;
            this.includeRam = includeRam;
        }

        public String getName() { return name; }
        public String getDescription() { return description; }
        public boolean isIncludeRam() { return includeRam; }
    }

    public static class MockTask {
        private final String upid;
        private final String type;
        private final String status;
        private final String exitStatus;

        public MockTask(String upid, String type, String status, String exitStatus) {
            this.upid = upid;
            this.type = type;
            this.status = status;
            this.exitStatus = exitStatus;
        }

        public String getUpid() { return upid; }
        public String getType() { return type; }
        public String getStatus() { return status; }
        public String getExitStatus() { return exitStatus; }
    }
}
