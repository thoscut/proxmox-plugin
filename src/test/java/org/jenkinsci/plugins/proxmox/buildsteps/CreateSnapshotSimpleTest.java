package org.jenkinsci.plugins.proxmox.buildsteps;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class CreateSnapshotSimpleTest {

    @Test
    void should_create_build_step_with_correct_properties() {
        // Given
        String datacenterDescription = "test-datacenter";
        String datacenterNode = "test-node";
        String vmId = "100";
        String snapshotName = "test-snapshot";
        String description = "Test snapshot description";
        boolean includeRam = true;

        // When
        CreateSnapshot step = new CreateSnapshot(
            datacenterDescription,
            datacenterNode,
            vmId,
            snapshotName,
            description,
            includeRam
        );

        // Then
        assertThat("Step should be created", step, notNullValue());
        assertThat("Datacenter description should match", step.getDatacenterDescription(), is(datacenterDescription));
        assertThat("Datacenter node should match", step.getDatacenterNode(), is(datacenterNode));
        assertThat("VM ID should match", step.getVmId(), is(vmId));
        assertThat("Snapshot name should match", step.getSnapshotName(), is(snapshotName));
        assertThat("Description should match", step.getDescription(), is(description));
        assertThat("Include RAM should match", step.getIncludeRam(), is(includeRam));
    }

    @Test
    void should_have_correct_descriptor_display_name() {
        // Given
        CreateSnapshot.DescriptorImpl descriptor = new CreateSnapshot.DescriptorImpl();

        // Then
        assertThat("Display name should be correct",
                   descriptor.getDisplayName(), is("Create Snapshot"));
    }
}