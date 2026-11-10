package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.model.Descriptor;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

    @Test
    void the_error_points_at_the_executors_field() {
        Descriptor.FormException e = assertThrows(
                Descriptor.FormException.class,
                () -> VirtualMachineSlave.checkExecutorsForRevertPolicy(2, RevertPolicy.BEFORE_JOB));
        assertThat(e.getFormField(), is("numExecutors"));
        assertThat(e.getMessage(), containsString("Before every job"));
    }

    @ParameterizedTest
    @CsvSource({"0, BEFORE_JOB", "1, BEFORE_JOB", "-1, BEFORE_JOB", "2, AFTER_CONNECT", "100, AFTER_CONNECT"})
    void only_several_executors_with_before_job_are_rejected(int numExecutors, RevertPolicy policy) {
        assertDoesNotThrow(() -> VirtualMachineSlave.checkExecutorsForRevertPolicy(numExecutors, policy));
    }
}
