package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;

import hudson.model.Node;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.junit.jupiter.api.Test;

/**
 * Tests for ProxmoxPlannedNode cloud-stats integration.
 * This is a new component added for cloud-stats plugin compatibility.
 */
class ProxmoxPlannedNodeTest {

    @Test
    void should_create_planned_node_with_provisioning_id() {
        // Given
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("test-cloud", "test-template", "test-node");
        String cloudName = "test-datacenter";
        String templateName = "template-1";
        int numExecutors = 2;

        Future<Node> future = CompletableFuture.completedFuture(null);

        // When
        ProxmoxPlannedNode plannedNode = new ProxmoxPlannedNode(id, cloudName, templateName, future, numExecutors);

        // Then
        assertThat("PlannedNode should be created", plannedNode, notNullValue());
        assertThat("Should store provisioning ID", plannedNode.getId(), is(id));
        assertThat("Should store cloud name", plannedNode.getCloudName(), is(cloudName));
        assertThat("Should store template name", plannedNode.getTemplateName(), is(templateName));
    }

    @Test
    void should_return_correct_provisioning_id() {
        // Given
        ProvisioningActivity.Id expectedId = new ProvisioningActivity.Id("cloud-1", "template-1", "node-1");
        Future<Node> future = CompletableFuture.completedFuture(null);

        ProxmoxPlannedNode plannedNode = new ProxmoxPlannedNode(
            expectedId,
            "cloud-1",
            "template-1",
            future,
            1
        );

        // When
        ProvisioningActivity.Id actualId = plannedNode.getId();

        // Then
        assertThat("Should return the same ID instance", actualId, sameInstance(expectedId));
    }

    @Test
    void should_extend_tracked_planned_node() {
        // Given
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("cloud", "template", "node");
        Future<Node> future = CompletableFuture.completedFuture(null);

        // When
        ProxmoxPlannedNode plannedNode = new ProxmoxPlannedNode(id, "cloud", "template", future, 1);

        // Then
        assertThat("Should extend TrackedPlannedNode", plannedNode instanceof TrackedPlannedNode);
    }

    @Test
    void should_store_cloud_and_template_names() {
        // Given
        String expectedCloud = "proxmox-datacenter-1";
        String expectedTemplate = "ubuntu-22.04-template";
        ProvisioningActivity.Id id = new ProvisioningActivity.Id(expectedCloud, expectedTemplate, "node");
        Future<Node> future = CompletableFuture.completedFuture(null);

        // When
        ProxmoxPlannedNode plannedNode = new ProxmoxPlannedNode(id, expectedCloud, expectedTemplate, future, 3);

        // Then
        assertThat("Should store correct cloud name", plannedNode.getCloudName(), is(expectedCloud));
        assertThat("Should store correct template name", plannedNode.getTemplateName(), is(expectedTemplate));
    }

    @Test
    void should_handle_multiple_executors() {
        // Given
        int expectedExecutors = 5;
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("cloud", "template", "node");
        Future<Node> future = CompletableFuture.completedFuture(null);

        // When
        ProxmoxPlannedNode plannedNode = new ProxmoxPlannedNode(id, "cloud", "template", future, expectedExecutors);

        // Then
        assertThat("PlannedNode should be created with executors", plannedNode, notNullValue());
        // Note: numExecutors is passed to parent constructor, we verify it doesn't throw
    }

    @Test
    void should_create_unique_ids_for_different_nodes() {
        // Given
        ProvisioningActivity.Id id1 = new ProvisioningActivity.Id("cloud", "template", "node-1");
        ProvisioningActivity.Id id2 = new ProvisioningActivity.Id("cloud", "template", "node-2");
        Future<Node> future = CompletableFuture.completedFuture(null);

        // When
        ProxmoxPlannedNode plannedNode1 = new ProxmoxPlannedNode(id1, "cloud", "template", future, 1);
        ProxmoxPlannedNode plannedNode2 = new ProxmoxPlannedNode(id2, "cloud", "template", future, 1);

        // Then
        assertThat("First node should have first ID", plannedNode1.getId(), sameInstance(id1));
        assertThat("Second node should have second ID", plannedNode2.getId(), sameInstance(id2));
    }
}
