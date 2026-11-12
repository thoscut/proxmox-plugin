package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for the computer of a {@link VirtualMachineSlave}, an agent that is bound by hand to an
 * existing virtual machine.
 */
@WithJenkins
class VirtualMachineSlaveComputerTest {

    private static VirtualMachineSlave createSlave(String name) throws Exception {
        return new VirtualMachineSlave(
                name,
                "Test agent",
                "/home/jenkins",
                "1",
                Node.Mode.NORMAL,
                "test",
                new JNLPLauncher(),
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                "test-datacenter",
                "pve-node",
                100,
                "snapshot",
                true,
                60,
                null);
    }

    /**
     * Cloud agents are treated differently by Jenkins and other plugins: Configuration as Code, for
     * example, neither exports them nor removes them. That must not happen to these agents.
     */
    @Test
    void is_not_a_cloud_agent(JenkinsRule r) throws Exception {
        VirtualMachineSlave slave = createSlave("vm-agent");

        assertThat(slave, not(instanceOf(AbstractCloudSlave.class)));
        assertThat(slave.createComputer(), not(instanceOf(AbstractCloudComputer.class)));
    }

    @Test
    void belongs_to_its_node(JenkinsRule r) throws Exception {
        VirtualMachineSlave slave = createSlave("vm-agent");
        r.jenkins.addNode(slave);

        Computer computer = slave.toComputer();

        assertThat(computer, instanceOf(VirtualMachineSlaveComputer.class));
        assertThat(computer.getName(), is("vm-agent"));
        assertThat(((VirtualMachineSlaveComputer) computer).getNode(), sameInstance(slave));
    }

    @Test
    void exposes_the_virtual_machine_for_the_status_page(JenkinsRule r) throws Exception {
        VirtualMachineSlave slave = createSlave("vm-agent");
        r.jenkins.addNode(slave);
        VirtualMachineSlaveComputer computer = (VirtualMachineSlaveComputer) slave.toComputer();

        assertThat(computer.getVmId(), is("100"));
        assertThat(computer.getProxmoxNode(), is("pve-node"));
        assertThat(computer.getDatacenterDescription(), is("test-datacenter"));
        assertThat(computer.getSnapshotName(), is("snapshot"));
        assertThat(computer.getRevertPolicy(), is("NEVER"));
        assertThat(computer.isStartVmEnabled(), is(true));
        assertThat(computer.getStartupWaitTime(), is(60));
    }

    @Test
    void status_page_copes_with_a_removed_node(JenkinsRule r) throws Exception {
        VirtualMachineSlave slave = createSlave("vm-agent");
        r.jenkins.addNode(slave);
        VirtualMachineSlaveComputer computer = (VirtualMachineSlaveComputer) slave.toComputer();

        r.jenkins.removeNode(slave);

        assertThat(computer.getVmId(), is("Unknown"));
        assertThat(computer.getProxmoxNode(), is("Unknown"));
        assertThat(computer.getDatacenterDescription(), is("Unknown"));
        assertThat(computer.getSnapshotName(), is("None"));
        assertThat(computer.getRevertPolicy(), is("NEVER"));
        assertThat(computer.isStartVmEnabled(), is(false));
        assertThat(computer.getStartupWaitTime(), is(0));
    }
}
