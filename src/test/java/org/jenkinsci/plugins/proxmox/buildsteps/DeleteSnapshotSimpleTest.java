package org.jenkinsci.plugins.proxmox.buildsteps;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DeleteSnapshotSimpleTest {

    @Test
    void should_create_build_step_with_correct_properties() {
        // Given
        String datacenterDescription = "test-datacenter";
        String datacenterNode = "test-node";
        String vmId = "100";
        String snapshotName = "test-snapshot";

        // When
        DeleteSnapshot step = new DeleteSnapshot(
            datacenterDescription,
            datacenterNode,
            vmId,
            snapshotName
        );

        // Then
        assertThat("Step should be created", step, notNullValue());
        assertThat("Datacenter description should match", step.getDatacenterDescription(), is(datacenterDescription));
        assertThat("Datacenter node should match", step.getDatacenterNode(), is(datacenterNode));
        assertThat("VM ID should match", step.getVmId(), is(vmId));
        assertThat("Snapshot name should match", step.getSnapshotName(), is(snapshotName));
    }

    @Test
    void should_have_correct_descriptor_display_name() {
        // Given
        DeleteSnapshot.DescriptorImpl descriptor = new DeleteSnapshot.DescriptorImpl();

        // Then
        assertThat("Display name should be correct",
                   descriptor.getDisplayName(), is("Delete Snapshot"));
    }
}