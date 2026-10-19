package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.Computer;
import hudson.model.Node;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import hudson.slaves.RetentionStrategy;
import java.util.Collections;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.springframework.security.access.AccessDeniedException;

/**
 * Tests for {@link VirtualMachineSlave}.
 */
@WithJenkins
class VirtualMachineSlaveTest {

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
    void configuring_agent_requires_administer(JenkinsRule r) {
        r.jenkins.setSecurityRealm(r.createDummySecurityRealm());
        r.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.READ, Computer.CREATE, Computer.CONFIGURE)
                .everywhere()
                .to("user"));

        try (ACLContext ignored = ACL.as2(User.getById("user", true).impersonate2())) {
            assertThrows(AccessDeniedException.class, () -> createSlave(new JNLPLauncher()));
        }
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
    }
}
