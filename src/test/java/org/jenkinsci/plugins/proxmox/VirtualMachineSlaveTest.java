package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for VirtualMachineSlave cloud-stats integration and lifecycle management.
 */
@WithJenkins
class VirtualMachineSlaveTest {

    @Test
    void should_extend_abstract_cloud_slave(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave");

        // Then
        assertThat("Should extend AbstractCloudSlave", slave instanceof AbstractCloudSlave);
    }

    @Test
    void should_implement_tracked_item(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave");

        // Then
        assertThat("Should implement TrackedItem", slave instanceof TrackedItem);
    }

    @Test
    void should_track_provisioning_id_for_cloud_stats(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave");
        ProvisioningActivity.Id expectedId = new ProvisioningActivity.Id("cloud", "template", "node");

        // When
        slave.setProvisioningId(expectedId);
        ProvisioningActivity.Id actualId = slave.getId();

        // Then
        assertThat("Should return the set provisioning ID", actualId, sameInstance(expectedId));
    }

    @Test
    void should_return_null_id_when_not_set(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave");

        // When
        ProvisioningActivity.Id id = slave.getId();

        // Then
        assertThat("Should return null when ID not set", id, nullValue());
    }

    @Test
    void should_create_virtual_machine_slave_computer(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave");

        // When
        Computer computer = slave.createComputer();

        // Then
        assertThat("Should create computer", computer, notNullValue());
        assertThat("Should create VirtualMachineSlaveComputer", computer instanceof VirtualMachineSlaveComputer);
    }

    @Test
    void should_increment_builds_executed_counter(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave");
        slave.setLimitedBuildsCount(5);
        assertThat("Initial builds executed should be 0", slave.getBuildsExecuted(), is(0));

        // When
        slave.incrementBuildsExecuted();

        // Then
        assertThat("Builds executed should be incremented", slave.getBuildsExecuted(), is(1));
    }

    @Test
    void should_track_multiple_build_executions(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave");
        slave.setLimitedBuildsCount(10);

        // When
        for (int i = 0; i < 5; i++) {
            slave.incrementBuildsExecuted();
        }

        // Then
        assertThat("Should track all executions", slave.getBuildsExecuted(), is(5));
    }

    @Test
    void should_store_limited_builds_count(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave");
        int expectedLimit = 10;

        // When
        slave.setLimitedBuildsCount(expectedLimit);

        // Then
        assertThat("Should store limited builds count", slave.getLimitedBuildsCount(), is(expectedLimit));
    }

    @Test
    void should_have_zero_limited_builds_by_default(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave");

        // Then
        assertThat("Default limited builds count should be 0", slave.getLimitedBuildsCount(), is(0));
    }

    @Test
    void should_handle_terminate_when_datacenter_not_found(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave("test-slave", "nonexistent-datacenter");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TaskListener listener = createTaskListener(output);

        // When
        slave._terminate(listener);

        // Then
        String log = output.toString();
        assertThat("Should log warning about missing datacenter",
                   log.contains("Cannot terminate") && log.contains("datacenter not found"));
    }

    @Test
    void should_get_datacenter_description(JenkinsRule r) throws Exception {
        // Given
        String expectedDatacenter = "test-datacenter";
        VirtualMachineSlave slave = createTestSlave("test-slave", expectedDatacenter);

        // When
        String actualDatacenter = slave.getDatacenterDescription();

        // Then
        assertThat("Should return datacenter description", actualDatacenter, is(expectedDatacenter));
    }

    @Test
    void should_get_datacenter_node(JenkinsRule r) throws Exception {
        // Given
        String expectedNode = "pve-node-1";
        VirtualMachineSlave slave = createTestSlaveWithNode("test-slave", "datacenter", expectedNode);

        // When
        String actualNode = slave.getDatacenterNode();

        // Then
        assertThat("Should return datacenter node", actualNode, is(expectedNode));
    }

    @Test
    void should_get_virtual_machine_id(JenkinsRule r) throws Exception {
        // Given
        Integer expectedVmId = 100;
        VirtualMachineSlave slave = createTestSlaveWithVmId("test-slave", expectedVmId);

        // When
        Integer actualVmId = slave.getVirtualMachineId();

        // Then
        assertThat("Should return VM ID", actualVmId, is(expectedVmId));
    }

    @Test
    void should_get_snapshot_name(JenkinsRule r) throws Exception {
        // Given
        String expectedSnapshot = "pre-jenkins";
        VirtualMachineSlave slave = createTestSlaveWithSnapshot("test-slave", expectedSnapshot);

        // When
        String actualSnapshot = slave.getSnapshotName();

        // Then
        assertThat("Should return snapshot name", actualSnapshot, is(expectedSnapshot));
    }

    @Test
    void should_get_start_vm_flag(JenkinsRule r) throws Exception {
        // Given
        Boolean expectedStartVM = true;
        VirtualMachineSlave slave = createTestSlaveWithStartVM("test-slave", expectedStartVM);

        // When
        Boolean actualStartVM = slave.getStartVM();

        // Then
        assertThat("Should return start VM flag", actualStartVM, is(expectedStartVM));
    }

    @Test
    void should_get_startup_waiting_period(JenkinsRule r) throws Exception {
        // Given
        int expectedWaitTime = 120;
        VirtualMachineSlave slave = createTestSlaveWithWaitTime("test-slave", expectedWaitTime);

        // When
        int actualWaitTime = slave.getStartupWaitingPeriodSeconds();

        // Then
        assertThat("Should return startup waiting period", actualWaitTime, is(expectedWaitTime));
    }

    @Test
    void should_get_revert_policy(JenkinsRule r) throws Exception {
        // Given
        RevertPolicy expectedPolicy = RevertPolicy.AFTER_JOB;
        VirtualMachineSlave slave = createTestSlaveWithRevertPolicy("test-slave", expectedPolicy);

        // When
        RevertPolicy actualPolicy = slave.getRevertPolicy();

        // Then
        assertThat("Should return revert policy", actualPolicy, is(expectedPolicy));
    }

    // Helper methods for creating test slaves

    private VirtualMachineSlave createTestSlave(String name) throws Exception {
        return createTestSlave(name, "test-datacenter");
    }

    private VirtualMachineSlave createTestSlave(String name, String datacenter) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave",
            "/home/jenkins",
            "1",
            hudson.model.Node.Mode.NORMAL,
            "test",
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            datacenter,
            "pve-node",
            100,
            "snapshot",
            true,
            60,
            null
        );
    }

    private VirtualMachineSlave createTestSlaveWithNode(String name, String datacenter, String node) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave",
            "/home/jenkins",
            "1",
            hudson.model.Node.Mode.NORMAL,
            "test",
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            datacenter,
            node,
            100,
            "snapshot",
            true,
            60,
            null
        );
    }

    private VirtualMachineSlave createTestSlaveWithVmId(String name, Integer vmId) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave",
            "/home/jenkins",
            "1",
            hudson.model.Node.Mode.NORMAL,
            "test",
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            "datacenter",
            "pve-node",
            vmId,
            "snapshot",
            true,
            60,
            null
        );
    }

    private VirtualMachineSlave createTestSlaveWithSnapshot(String name, String snapshot) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave",
            "/home/jenkins",
            "1",
            hudson.model.Node.Mode.NORMAL,
            "test",
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            "datacenter",
            "pve-node",
            100,
            snapshot,
            true,
            60,
            null
        );
    }

    private VirtualMachineSlave createTestSlaveWithStartVM(String name, Boolean startVM) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave",
            "/home/jenkins",
            "1",
            hudson.model.Node.Mode.NORMAL,
            "test",
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            "datacenter",
            "pve-node",
            100,
            "snapshot",
            startVM,
            60,
            null
        );
    }

    private VirtualMachineSlave createTestSlaveWithWaitTime(String name, int waitTime) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave",
            "/home/jenkins",
            "1",
            hudson.model.Node.Mode.NORMAL,
            "test",
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            "datacenter",
            "pve-node",
            100,
            "snapshot",
            true,
            waitTime,
            null
        );
    }

    private VirtualMachineSlave createTestSlaveWithRevertPolicy(String name, RevertPolicy policy) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave",
            "/home/jenkins",
            "1",
            hudson.model.Node.Mode.NORMAL,
            "test",
            new JNLPLauncher(true),
            RetentionStrategy.NOOP,
            Collections.emptyList(),
            "datacenter",
            "pve-node",
            100,
            "snapshot",
            true,
            60,
            policy
        );
    }

    private TaskListener createTaskListener(ByteArrayOutputStream output) {
        return new TaskListener() {
            @Override
            public PrintStream getLogger() {
                return new PrintStream(output);
            }
        };
    }
}
