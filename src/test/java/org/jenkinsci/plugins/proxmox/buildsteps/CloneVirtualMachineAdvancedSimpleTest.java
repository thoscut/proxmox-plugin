package org.jenkinsci.plugins.proxmox.buildsteps;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import hudson.util.FormValidation;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CloneVirtualMachineAdvancedSimpleTest {

    @Test
    void should_create_build_step_with_correct_properties() {
        // Given
        String datacenterDescription = "test-datacenter";
        String datacenterNode = "test-node";
        String sourceVmId = "100";
        String targetVmId = "200";
        String cloneName = "test-clone";
        boolean fullClone = true;
        String snapshotName = "test-snapshot";
        boolean startAfterClone = true;
        int startupWaitSeconds = 30;

        // When
        CloneVirtualMachineAdvanced step = new CloneVirtualMachineAdvanced(
            datacenterDescription,
            datacenterNode,
            sourceVmId,
            targetVmId,
            cloneName,
            fullClone,
            snapshotName,
            startAfterClone,
            startupWaitSeconds
        );

        // Then
        assertThat("Step should be created", step, notNullValue());
        assertThat("Datacenter description should match", step.getDatacenterDescription(), is(datacenterDescription));
        assertThat("Datacenter node should match", step.getDatacenterNode(), is(datacenterNode));
        assertThat("VM ID should match", step.getVmId(), is(sourceVmId));
        assertThat("Target VM ID should match", step.getTargetVmId(), is(targetVmId));
        assertThat("Clone name should match", step.getCloneName(), is(cloneName));
        assertThat("Full clone should match", step.getFullClone(), is(fullClone));
        assertThat("Snapshot name should match", step.getSnapshotName(), is(snapshotName));
        assertThat("Start after clone should match", step.getStartAfterClone(), is(startAfterClone));
        assertThat("Startup wait seconds should match", step.getStartupWaitSeconds(), is(startupWaitSeconds));
    }

    @Test
    void should_have_correct_descriptor_display_name() {
        // Given
        CloneVirtualMachineAdvanced.DescriptorImpl descriptor = new CloneVirtualMachineAdvanced.DescriptorImpl();

        // Then
        assertThat("Display name should be correct",
                   descriptor.getDisplayName(), is("Clone Virtual Machine (Advanced)"));
    }

    @Test
    void should_validate_target_vm_id_correctly() {
        // Given
        CloneVirtualMachineAdvanced.DescriptorImpl descriptor = new CloneVirtualMachineAdvanced.DescriptorImpl();

        // When & Then - Valid VM ID
        FormValidation result = descriptor.doCheckTargetVmId("100");
        assertThat("Valid VM ID should be OK", result.kind, is(FormValidation.Kind.OK));

        // Invalid VM ID - not a number
        result = descriptor.doCheckTargetVmId("invalid");
        assertThat("Invalid VM ID should be ERROR", result.kind, is(FormValidation.Kind.ERROR));

        // Invalid VM ID - negative
        result = descriptor.doCheckTargetVmId("-1");
        assertThat("Negative VM ID should be ERROR", result.kind, is(FormValidation.Kind.ERROR));

        // Large VM ID - warning
        result = descriptor.doCheckTargetVmId("1000000");
        assertThat("Large VM ID should be WARNING", result.kind, is(FormValidation.Kind.WARNING));
    }

    @Test
    void should_validate_clone_name_correctly() {
        // Given
        CloneVirtualMachineAdvanced.DescriptorImpl descriptor = new CloneVirtualMachineAdvanced.DescriptorImpl();

        // When & Then - Valid name
        FormValidation result = descriptor.doCheckCloneName("valid-name-123");
        assertThat("Valid clone name should be OK", result.kind, is(FormValidation.Kind.OK));

        // Empty name
        result = descriptor.doCheckCloneName("");
        assertThat("Empty clone name should be ERROR", result.kind, is(FormValidation.Kind.ERROR));

        // Too long name
        result = descriptor.doCheckCloneName("a".repeat(65));
        assertThat("Too long clone name should be ERROR", result.kind, is(FormValidation.Kind.ERROR));

        // Invalid format
        result = descriptor.doCheckCloneName("invalid name with spaces");
        assertThat("Invalid format should be ERROR", result.kind, is(FormValidation.Kind.ERROR));
    }
}