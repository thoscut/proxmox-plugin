package org.jenkinsci.plugins.proxmox;

import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.NodeProvisioner.PlannedNode;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Proxmox implementation of PlannedNode that integrates with cloud-stats plugin.
 * Tracks the full lifecycle of VM provisioning from request to completion.
 */
public class ProxmoxPlannedNode extends TrackedPlannedNode {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxPlannedNode.class.getName());

    private final ProvisioningActivity.Id id;
    private final String cloudName;
    private final String templateName;

    public ProxmoxPlannedNode(@NonNull ProvisioningActivity.Id id,
                              @NonNull String cloudName,
                              @NonNull String templateName,
                              @NonNull Future<hudson.model.Node> future,
                              int numExecutors) {
        super(id, numExecutors, future);
        this.id = id;
        this.cloudName = cloudName;
        this.templateName = templateName;

        LOGGER.log(Level.FINE, "Created ProxmoxPlannedNode for {0}/{1} with ID {2}",
                  new Object[]{cloudName, templateName, id});
    }

    @NonNull
    @Override
    public ProvisioningActivity.Id getId() {
        return id;
    }

    public String getCloudName() {
        return cloudName;
    }

    public String getTemplateName() {
        return templateName;
    }
}
