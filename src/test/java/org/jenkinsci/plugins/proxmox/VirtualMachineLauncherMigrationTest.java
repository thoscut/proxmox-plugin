package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import hudson.slaves.JNLPLauncher;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests loading {@link VirtualMachineLauncher} configurations saved by older plugin versions.
 */
@WithJenkins
class VirtualMachineLauncherMigrationTest {

    @Test
    void migrates_delegate_and_wait_time(JenkinsRule r) {
        String xml = String.join(
                "\n",
                "<org.jenkinsci.plugins.proxmox.VirtualMachineLauncher>",
                "  <delegate class=\"hudson.slaves.JNLPLauncher\"/>",
                "  <datacenterDescription>user@pve - host</datacenterDescription>",
                "  <datacenterNode>pve1</datacenterNode>",
                "  <virtualMachineId>100</virtualMachineId>",
                "  <snapshotName>clean</snapshotName>",
                "  <startVM>true</startVM>",
                "  <WAIT__TIME__MS>30000</WAIT__TIME__MS>",
                "  <revertPolicy>BEFORE_JOB</revertPolicy>",
                "</org.jenkinsci.plugins.proxmox.VirtualMachineLauncher>");

        VirtualMachineLauncher launcher = (VirtualMachineLauncher) Jenkins.XSTREAM2.fromXML(xml);

        assertThat(launcher.getLauncher(), instanceOf(JNLPLauncher.class));
        assertThat(launcher.getWaitingTimeSecs(), is(30));
        assertThat(launcher.getDatacenterNode(), is("pve1"));
        assertThat(launcher.getVirtualMachineId(), is(100));
        assertThat(launcher.getSnapshotName(), is("clean"));
        assertThat(launcher.getRevertPolicy(), is(RevertPolicy.BEFORE_JOB));
        assertThat(launcher.isLaunchSupported(), is(true));
    }
}
