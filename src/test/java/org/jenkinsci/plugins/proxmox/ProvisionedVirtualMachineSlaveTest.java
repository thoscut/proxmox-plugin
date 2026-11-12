package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import hudson.util.StreamTaskListener;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for {@link ProvisionedVirtualMachineSlave} and its computer: the agents that the cloud
 * provisions by cloning a virtual machine.
 */
@WithJenkins
class ProvisionedVirtualMachineSlaveTest {

    private static final ProvisioningActivity.Id ID = new ProvisioningActivity.Id("cloud", "template", "clone-1");

    private static ProvisionedVirtualMachineSlave createSlave(String name, String datacenter) throws Exception {
        return new ProvisionedVirtualMachineSlave(
                name,
                "Test agent",
                "/home/jenkins",
                "1",
                Node.Mode.NORMAL,
                "test",
                new JNLPLauncher(),
                RetentionStrategy.NOOP,
                Collections.emptyList(),
                datacenter,
                "pve-node",
                100,
                "snapshot",
                true,
                60);
    }

    private static ProvisionedVirtualMachineSlave createSlave(String name) throws Exception {
        return createSlave(name, "test-datacenter");
    }

    @Test
    void is_a_cloud_agent_that_the_statistics_can_track(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");

        assertThat(slave, instanceOf(AbstractCloudSlave.class));
        assertThat(slave, instanceOf(TrackedItem.class));
        assertThat(slave.createComputer(), instanceOf(AbstractCloudComputer.class));
        assertThat(slave.createComputer(), instanceOf(TrackedItem.class));
    }

    @Test
    void exposes_its_virtual_machine(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");

        assertThat(slave.getDatacenterDescription(), is("test-datacenter"));
        assertThat(slave.getDatacenterNode(), is("pve-node"));
        assertThat(slave.getVirtualMachineId(), is(100));
        assertThat(slave.getNumExecutors(), is(1));
        assertThat(slave.getLabelString(), is("test"));
    }

    @Test
    void starts_its_virtual_machine_but_never_reverts_it(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");

        assertThat(slave.getLauncher(), instanceOf(VirtualMachineLauncher.class));
        VirtualMachineLauncher launcher = (VirtualMachineLauncher) slave.getLauncher();
        assertThat(launcher.getLauncher(), instanceOf(JNLPLauncher.class));
        assertThat(launcher.getDatacenterDescription(), is("test-datacenter"));
        assertThat(launcher.getDatacenterNode(), is("pve-node"));
        assertThat(launcher.getVirtualMachineId(), is(100));
        assertThat(launcher.getWaitingTimeSecs(), is(60));
        assertThat(launcher.getRevertPolicy(), is(RevertPolicy.NEVER));
    }

    @Test
    void is_not_offered_as_a_new_node(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");

        assertThat(slave.getDescriptor().isInstantiable(), is(false));
    }

    @Test
    void tracks_the_provisioning_id(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");
        assertThat(slave.getId(), nullValue());

        slave.setProvisioningId(ID);

        assertThat(slave.getId(), sameInstance(ID));
    }

    @Test
    void computer_reports_the_provisioning_id_before_the_node_is_added(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");
        slave.setProvisioningId(ID);

        assertThat(slave.createComputer().getId(), sameInstance(ID));
    }

    @Test
    void computer_reports_the_provisioning_id_of_its_node(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");
        slave.setProvisioningId(ID);
        r.jenkins.addNode(slave);

        ProvisionedVirtualMachineSlaveComputer computer = (ProvisionedVirtualMachineSlaveComputer) slave.toComputer();

        assertThat(computer.getNode(), sameInstance(slave));
        assertThat(computer.getName(), is("clone-1"));
        assertThat(computer.getId(), sameInstance(ID));
    }

    @Test
    void computer_without_provisioning_id_reports_none(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");
        r.jenkins.addNode(slave);
        ProvisionedVirtualMachineSlaveComputer computer = slave.createComputer();

        assertThat(computer.getId(), nullValue());

        // The computer may outlive its node
        r.jenkins.removeNode(slave);
        assertThat(computer.getId(), nullValue());
    }

    @Test
    void counts_the_builds_it_executed(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");
        assertThat(slave.getLimitedBuildsCount(), is(0));
        slave.setLimitedBuildsCount(10);
        assertThat(slave.getLimitedBuildsCount(), is(10));
        assertThat(slave.getBuildsExecuted(), is(0));

        for (int i = 0; i < 5; i++) {
            slave.incrementBuildsExecuted();
        }

        assertThat(slave.getBuildsExecuted(), is(5));
    }

    @Test
    void is_removed_once_the_limit_of_builds_is_reached(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1", "nonexistent-datacenter");
        slave.setLimitedBuildsCount(2);
        r.jenkins.addNode(slave);

        slave.incrementBuildsExecuted();
        assertThat(r.jenkins.getNode("clone-1"), sameInstance(slave));

        slave.incrementBuildsExecuted();
        assertThat(r.jenkins.getNode("clone-1"), nullValue());
    }

    @Test
    void terminating_reports_a_missing_datacenter(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1", "nonexistent-datacenter");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        TaskListener listener = new StreamTaskListener(output, StandardCharsets.UTF_8);

        slave._terminate(listener);

        String log = output.toString(StandardCharsets.UTF_8);
        assertThat(log, containsString("Cannot terminate"));
        assertThat(log, containsString("datacenter not found"));
    }

    /**
     * The agent is defined by its template. Rebuilding it from the submitted form would lose the
     * link to its cloned virtual machine, so the form is only shown.
     */
    @Test
    void submitting_the_configuration_page_keeps_the_agent(JenkinsRule r) throws Exception {
        ProvisionedVirtualMachineSlave slave = createSlave("clone-1");
        slave.setProvisioningId(ID);
        r.jenkins.addNode(slave);

        var page = r.createWebClient().goTo("computer/clone-1/configure");
        assertThat(page.asNormalizedText(), containsString("provisioned from a template"));
        r.submit(page.getFormByName("config"));

        assertThat(r.jenkins.getNode("clone-1"), sameInstance(slave));
        assertThat(slave.getId(), sameInstance(ID));
    }
}
