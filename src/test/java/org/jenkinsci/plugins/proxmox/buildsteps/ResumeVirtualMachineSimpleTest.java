package org.jenkinsci.plugins.proxmox.buildsteps;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class ResumeVirtualMachineSimpleTest {

    @Test
    void should_create_build_step_with_correct_properties() {
        // Given
        String datacenterDescription = "test-datacenter";
        String datacenterNode = "test-node";
        String vmId = "100";
        int waitSeconds = 30;

        // When
        ResumeVirtualMachine step = new ResumeVirtualMachine(
            datacenterDescription,
            datacenterNode,
            vmId,
            waitSeconds
        );

        // Then
        assertThat("Step should be created", step, notNullValue());
        assertThat("Datacenter description should match", step.getDatacenterDescription(), is(datacenterDescription));
        assertThat("Datacenter node should match", step.getDatacenterNode(), is(datacenterNode));
        assertThat("VM ID should match", step.getVmId(), is(vmId));
        assertThat("Wait seconds should match", step.getWaitSeconds(), is(waitSeconds));
    }

    @Test
    void should_have_correct_descriptor_display_name() {
        // Given
        ResumeVirtualMachine.DescriptorImpl descriptor = new ResumeVirtualMachine.DescriptorImpl();

        // Then
        assertThat("Display name should be correct",
                   descriptor.getDisplayName(), is("Resume Virtual Machine"));
    }
}