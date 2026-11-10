package org.jenkinsci.plugins.proxmox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.Descriptor;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VirtualMachineSlave} that need no running Jenkins.
 */
class VirtualMachineSlaveTest {

    @Test
    void reverting_before_every_job_needs_a_single_executor() {
        assertThrows(
                Descriptor.FormException.class,
                () -> VirtualMachineSlave.checkExecutorsForRevertPolicy(2, RevertPolicy.BEFORE_JOB));
    }

    @Test
    void other_configurations_may_use_several_executors() {
        assertDoesNotThrow(() -> VirtualMachineSlave.checkExecutorsForRevertPolicy(1, RevertPolicy.BEFORE_JOB));
        assertDoesNotThrow(() -> VirtualMachineSlave.checkExecutorsForRevertPolicy(4, RevertPolicy.AFTER_CONNECT));
        assertDoesNotThrow(() -> VirtualMachineSlave.checkExecutorsForRevertPolicy(4, null));
    }
}
