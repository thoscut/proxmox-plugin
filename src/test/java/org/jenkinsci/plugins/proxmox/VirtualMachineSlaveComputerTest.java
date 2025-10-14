package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import hudson.model.Computer;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for VirtualMachineSlaveComputer cloud-stats integration and computer management.
 */
@WithJenkins
class VirtualMachineSlaveComputerTest {

    @Test
    void should_extend_abstract_cloud_computer(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");

        // When
        Computer computer = slave.createComputer();

        // Then
        assertThat("Computer should extend AbstractCloudComputer",
                   computer instanceof AbstractCloudComputer);
    }

    @Test
    void should_implement_tracked_item(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");

        // When
        Computer computer = slave.createComputer();

        // Then
        assertThat("Computer should implement TrackedItem",
                   computer instanceof TrackedItem);
    }

    @Test
    void should_return_provisioning_id_from_slave(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");
        ProvisioningActivity.Id expectedId = new ProvisioningActivity.Id("cloud", "template", "node");
        slave.setProvisioningId(expectedId);

        // When
        VirtualMachineSlaveComputer computer = (VirtualMachineSlaveComputer) slave.createComputer();
        ProvisioningActivity.Id actualId = computer.getId();

        // Then
        assertThat("Computer should return slave's provisioning ID",
                   actualId, sameInstance(expectedId));
    }

    @Test
    void should_return_null_when_slave_has_no_provisioning_id(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");
        // Don't set provisioning ID

        // When
        VirtualMachineSlaveComputer computer = (VirtualMachineSlaveComputer) slave.createComputer();
        ProvisioningActivity.Id id = computer.getId();

        // Then
        assertThat("Computer should return null when slave has no ID",
                   id, nullValue());
    }

    @Test
    void should_return_null_when_node_is_null(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");
        VirtualMachineSlaveComputer computer = (VirtualMachineSlaveComputer) slave.createComputer();

        // Remove the node from Jenkins (simulating a deleted node)
        r.jenkins.removeNode(slave);

        // When
        ProvisioningActivity.Id id = computer.getId();

        // Then
        // Computer.getNode() will return null after node is removed
        // getId() should handle this gracefully
        assertThat("Computer should handle null node gracefully",
                   id, nullValue());
    }

    @Test
    void should_create_computer_with_correct_type(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");

        // When
        Computer computer = slave.createComputer();

        // Then
        assertThat("Should create VirtualMachineSlaveComputer",
                   computer instanceof VirtualMachineSlaveComputer);
        assertThat("Computer should be created", computer, notNullValue());
    }

    @Test
    void should_get_node_from_computer(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");
        r.jenkins.addNode(slave);

        // When
        VirtualMachineSlaveComputer computer = (VirtualMachineSlaveComputer) slave.toComputer();

        // Then
        assertThat("Computer should have node", computer.getNode(), notNullValue());
        assertThat("Computer should reference correct slave",
                   computer.getNode(), sameInstance(slave));
    }

    @Test
    void should_handle_multiple_computers_with_different_provisioning_ids(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave1 = createTestSlave(r, "test-slave-1");
        VirtualMachineSlave slave2 = createTestSlave(r, "test-slave-2");

        ProvisioningActivity.Id id1 = new ProvisioningActivity.Id("cloud", "template", "node-1");
        ProvisioningActivity.Id id2 = new ProvisioningActivity.Id("cloud", "template", "node-2");

        slave1.setProvisioningId(id1);
        slave2.setProvisioningId(id2);

        // When
        VirtualMachineSlaveComputer computer1 = (VirtualMachineSlaveComputer) slave1.createComputer();
        VirtualMachineSlaveComputer computer2 = (VirtualMachineSlaveComputer) slave2.createComputer();

        // Then
        assertThat("Computer 1 should have ID 1", computer1.getId(), sameInstance(id1));
        assertThat("Computer 2 should have ID 2", computer2.getId(), sameInstance(id2));
    }

    @Test
    void should_have_proper_computer_name(JenkinsRule r) throws Exception {
        // Given
        String expectedName = "test-agent";
        VirtualMachineSlave slave = createTestSlave(r, expectedName);
        r.jenkins.addNode(slave);

        // When
        VirtualMachineSlaveComputer computer = (VirtualMachineSlaveComputer) slave.toComputer();

        // Then
        assertThat("Computer should have correct name",
                   computer.getName(), is(expectedName));
    }

    @Test
    void should_track_provisioning_through_computer_lifecycle(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("datacenter", "ubuntu-template", "agent-1");
        slave.setProvisioningId(id);

        r.jenkins.addNode(slave);

        // When - Get computer at different stages
        VirtualMachineSlaveComputer computer1 = (VirtualMachineSlaveComputer) slave.toComputer();
        ProvisioningActivity.Id idBeforeOnline = computer1.getId();

        // Computer created and still has ID
        VirtualMachineSlaveComputer computer2 = (VirtualMachineSlaveComputer) slave.toComputer();
        ProvisioningActivity.Id idAfterCreation = computer2.getId();

        // Then - ID should be consistent throughout lifecycle
        assertThat("ID should be set before online", idBeforeOnline, sameInstance(id));
        assertThat("ID should remain after creation", idAfterCreation, sameInstance(id));
    }

    @Test
    void should_handle_computer_creation_without_adding_to_jenkins(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("cloud", "template", "node");
        slave.setProvisioningId(id);

        // When - Create computer without adding to Jenkins
        VirtualMachineSlaveComputer computer = slave.createComputer();

        // Then - Should still have provisioning ID
        assertThat("Computer should have provisioning ID even without Jenkins",
                   computer.getId(), sameInstance(id));
    }

    @Test
    void should_support_generic_tracked_item_interface(JenkinsRule r) throws Exception {
        // Given
        VirtualMachineSlave slave = createTestSlave(r, "test-slave");
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("cloud", "template", "node");
        slave.setProvisioningId(id);

        // When
        Computer computer = slave.createComputer();
        TrackedItem trackedItem = (TrackedItem) computer;

        // Then
        assertThat("TrackedItem interface should work",
                   trackedItem.getId(), sameInstance(id));
    }

    // Helper method to create test slaves
    private VirtualMachineSlave createTestSlave(JenkinsRule r, String name) throws Exception {
        return new VirtualMachineSlave(
            name,
            "Test slave for " + name,
            "/home/jenkins",
            "1",
            hudson.model.Node.Mode.NORMAL,
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
}
