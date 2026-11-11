package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

import hudson.model.Node;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests that {@link VirtualMachineSlave} keeps its Proxmox launcher and its settings when the
 * launcher is replaced and when the agent is saved and loaded again.
 */
@WithJenkins
class VirtualMachineSlavePersistenceTest {

    private static VirtualMachineSlave createSlave(ComputerLauncher launcher) throws Exception {
        return new VirtualMachineSlave(
                "vm-agent",
                "description",
                "/home/jenkins",
                "1",
                Node.Mode.NORMAL,
                "proxmox",
                launcher,
                new RetentionStrategy.Always(),
                Collections.emptyList(),
                "user@pve - host",
                "pve1",
                100,
                "clean",
                true,
                30,
                RevertPolicy.AFTER_CONNECT);
    }

    @Test
    void wraps_delegate_launcher(JenkinsRule r) throws Exception {
        JNLPLauncher jnlp = new JNLPLauncher();
        VirtualMachineSlave slave = createSlave(jnlp);

        assertThat(slave.getLauncher(), instanceOf(VirtualMachineLauncher.class));
        assertThat(slave.getDelegateLauncher(), is(sameInstance(jnlp)));
    }

    @Test
    void keeps_wrapper_when_launcher_is_replaced(JenkinsRule r) throws Exception {
        VirtualMachineSlave slave = createSlave(new JNLPLauncher());
        JNLPLauncher replacement = new JNLPLauncher();

        slave.setLauncher(replacement);

        assertThat(slave.getLauncher(), instanceOf(VirtualMachineLauncher.class));
        assertThat(slave.getDelegateLauncher(), is(sameInstance(replacement)));
        VirtualMachineLauncher launcher = (VirtualMachineLauncher) slave.getLauncher();
        assertThat(launcher.getDatacenterNode(), is("pve1"));
        assertThat(launcher.getVirtualMachineId(), is(100));
        assertThat(launcher.getSnapshotName(), is("clean"));
        assertThat(launcher.getWaitingTimeSecs(), is(30));
        assertThat(launcher.getRevertPolicy(), is(RevertPolicy.AFTER_CONNECT));
    }

    @Test
    void survives_save_and_reload(JenkinsRule r) throws Exception {
        VirtualMachineSlave slave = createSlave(new JNLPLauncher());

        VirtualMachineSlave reloaded = (VirtualMachineSlave) Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(slave));

        assertThat(reloaded.getLauncher(), instanceOf(VirtualMachineLauncher.class));
        assertThat(reloaded.getDelegateLauncher(), instanceOf(JNLPLauncher.class));
        VirtualMachineLauncher launcher = (VirtualMachineLauncher) reloaded.getLauncher();
        assertThat(launcher.getDatacenterDescription(), is("user@pve - host"));
        assertThat(launcher.getVirtualMachineId(), is(100));
        assertThat(launcher.getSnapshotName(), is("clean"));
        assertThat(launcher.getWaitingTimeSecs(), is(30));
        assertThat(reloaded.getNumExecutors(), is(1));
        assertThat(reloaded.getLabelString(), is("proxmox"));
        assertThat(reloaded.getNodeDescription(), is("description"));
    }

    /**
     * Only provisioned agents own (and delete) their virtual machine, so this must not get lost
     * or appear out of nowhere when Jenkins restarts.
     */
    @Test
    void ownership_of_the_virtual_machine_survives_save_and_reload(JenkinsRule r) throws Exception {
        VirtualMachineSlave manual = createSlave(new JNLPLauncher());
        VirtualMachineSlave provisioned = createSlave(new JNLPLauncher());
        provisioned.setProvisioningId(new ProvisioningActivity.Id("cloud", "template", "vm-agent"));

        VirtualMachineSlave manualReloaded =
                (VirtualMachineSlave) Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(manual));
        VirtualMachineSlave provisionedReloaded =
                (VirtualMachineSlave) Jenkins.XSTREAM2.fromXML(Jenkins.XSTREAM2.toXML(provisioned));

        assertThat(manualReloaded.isProvisionedByCloud(), is(false));
        assertThat(provisionedReloaded.isProvisionedByCloud(), is(true));
    }
}
