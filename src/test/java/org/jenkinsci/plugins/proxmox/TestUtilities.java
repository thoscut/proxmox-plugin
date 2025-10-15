package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Node;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.pve2api.MockProxmoxConnector;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.Collections;
import hudson.model.Descriptor;

/**
 * Utility class providing common test fixtures and helper methods.
 */
public class TestUtilities {

    /**
     * Creates a basic test slave with minimal configuration.
     */
    public static VirtualMachineSlave createTestSlave(String name) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave for " + name,
            "/home/jenkins",
            "1",
            Node.Mode.NORMAL,
            "test",
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            "test-datacenter",
            "pve-node",
            100,
            "snapshot",
            true,
            60,
            null
        );
    }

    /**
     * Creates a test slave with custom configuration.
     */
    public static VirtualMachineSlave createTestSlave(String name, String datacenter, String node,
                                                     int vmid, String snapshotName) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave for " + name,
            "/home/jenkins",
            "2",
            Node.Mode.NORMAL,
            "linux docker",
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            datacenter,
            node,
            vmid,
            snapshotName,
            true,
            90,
            null
        );
    }

    /**
     * Creates a mock Proxmox connector for testing.
     */
    public static MockProxmoxConnector createMockConnector() {
        return new MockProxmoxConnector(
            "test-proxmox.local",
            "jenkins",
            "pam",
            Secret.fromString("test-password"),
            true
        );
    }

    /**
     * Creates a mock Proxmox connector with custom configuration.
     */
    public static MockProxmoxConnector createMockConnector(String hostname, String username,
                                                          String realm, String password) {
        return new MockProxmoxConnector(
            hostname,
            username,
            realm,
            Secret.fromString(password),
            true
        );
    }

    /**
     * Sets up test VMs in the mock connector.
     */
    public static void setupTestVMs(MockProxmoxConnector connector) {
        // Add template VM
        MockProxmoxConnector.MockVM template = connector.addVM("pve-node1", 100, "ubuntu-template", "stopped");
        template.setTemplate(true);
        template.addSnapshot("base-snapshot", "Base snapshot for cloning", true);

        // Add running VM
        connector.addVM("pve-node1", 101, "test-vm-1", "running");

        // Add stopped VM
        connector.addVM("pve-node2", 102, "test-vm-2", "stopped");
    }

    /**
     * Creates and stores credentials in Jenkins for testing.
     */
    public static String createTestCredentials(JenkinsRule j, String username, String password) throws Exception {
        String credentialsId = "test-credentials-" + System.currentTimeMillis();

        UsernamePasswordCredentialsImpl credentials = new UsernamePasswordCredentialsImpl(
            CredentialsScope.GLOBAL,
            credentialsId,
            "Test Credentials",
            username,
            password
        );

        CredentialsStore store = CredentialsProvider.lookupStores(j.jenkins).iterator().next();
        store.addCredentials(Domain.global(), credentials);

        return credentialsId;
    }

    /**
     * Creates a test Datacenter with basic configuration.
     */
    public static Datacenter createTestDatacenter(String hostname, String credentialsId) {
        return new Datacenter(
            hostname,
            credentialsId,
            "pam",
            true,
            Collections.emptyList(),
            10
        );
    }

    /**
     * Creates a test Datacenter with templates.
     */
    public static Datacenter createTestDatacenterWithTemplates(JenkinsRule j, String hostname,
                                                               String credentialsId) {
        ProxmoxCloudSlaveTemplate template = createTestTemplate("ubuntu-template", "linux", "pve-node1", "100");

        return new Datacenter(
            hostname,
            credentialsId,
            "pam",
            true,
            Collections.singletonList(template),
            10
        );
    }

    /**
     * Creates a test ProxmoxCloudSlaveTemplate with minimal configuration.
     */
    public static ProxmoxCloudSlaveTemplate createTestTemplate(String templateName, String labels,
                                                              String datacenterNode, String templateVmId) {
        ProxmoxCloudSlaveTemplate template = new ProxmoxCloudSlaveTemplate(
            templateName,
            labels,
            "/home/jenkins",
            "2",
            datacenterNode,
            templateVmId,
            "base-snapshot",
            5,
            30,
            0,
            true,
            true,
            60,
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            null,
            60,
            false
        );
        // Set optional fields via setters
        template.setWaitForGuestAgent(false);
        template.setWaitForGuestAgentTimeoutSeconds(30);
        return template;
    }

    /**
     * Creates a test ProxmoxCloudSlaveTemplate with full configuration.
     */
    public static ProxmoxCloudSlaveTemplate createTestTemplate(String templateName, String labels,
                                                              String datacenterNode, String templateVmId,
                                                              String snapshotName, int instanceCap,
                                                              boolean startVM, boolean linkedClone) {
        ProxmoxCloudSlaveTemplate template = new ProxmoxCloudSlaveTemplate(
            templateName,
            labels,
            "/home/jenkins",
            "2",
            datacenterNode,
            templateVmId,
            snapshotName,
            instanceCap,
            30,
            0,
            startVM,
            linkedClone,
            60,
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            null,
            60,
            false
        );
        // Set optional fields via setters
        template.setWaitForGuestAgent(true);
        template.setWaitForGuestAgentTimeoutSeconds(30);
        return template;
    }

    /**
     * Creates a QemuGuestAgentLauncher with default settings.
     */
    public static QemuGuestAgentLauncher createQemuGuestAgentLauncher() {
        return new QemuGuestAgentLauncher(
            null, // default command
            60,   // connection timeout
            3,    // max retries
            true, // wait for agent ready
            false, // use WebSocket
            null, // default work dir
            false, // curl ssl no revoke
            false  // use direct
        );
    }

    /**
     * Creates a QemuGuestAgentLauncher with custom settings.
     */
    public static QemuGuestAgentLauncher createQemuGuestAgentLauncher(String agentCommand,
                                                                      int connectionTimeout,
                                                                      int maxRetries,
                                                                      boolean waitForReady,
                                                                      boolean useWebSocket,
                                                                      boolean useDirect) {
        return new QemuGuestAgentLauncher(
            agentCommand,
            connectionTimeout,
            maxRetries,
            waitForReady,
            useWebSocket,
            null,
            false,
            useDirect
        );
    }

    /**
     * Waits for a condition to be true with timeout.
     */
    public static boolean waitForCondition(ConditionChecker checker, long timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (checker.check()) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @FunctionalInterface
    public interface ConditionChecker {
        boolean check();
    }
}
