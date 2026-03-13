package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link VirtualMachineLauncher}.
 * Validates field persistence, null safety, and enum behavior.
 */
class VirtualMachineLauncherTest {

    /**
     * Fields that must be persisted (not transient) to survive Jenkins restarts.
     * This was a critical bug where these fields were incorrectly marked transient.
     */
    private static final List<String> PERSISTED_FIELDS = List.of(
            "datacenterDescription",
            "datacenterNode",
            "virtualMachineId",
            "snapshotName",
            "startVM",
            "waitingTimeSecs",
            "revertPolicy");

    @ParameterizedTest
    @ValueSource(
            strings = {
                "datacenterDescription",
                "datacenterNode",
                "virtualMachineId",
                "snapshotName",
                "startVM",
                "waitingTimeSecs",
                "revertPolicy"
            })
    void persisted_fields_must_not_be_transient(String fieldName) throws NoSuchFieldException {
        Field field = VirtualMachineLauncher.class.getDeclaredField(fieldName);
        assertFalse(
                Modifier.isTransient(field.getModifiers()),
                "Field '" + fieldName + "' must NOT be transient to survive Jenkins restarts");
    }

    @Test
    void revert_policy_enum_values() {
        assertThat(RevertPolicy.values().length, is(2));
        assertThat(RevertPolicy.AFTER_CONNECT, is(notNullValue()));
        assertThat(RevertPolicy.BEFORE_JOB, is(notNullValue()));
        assertThat(RevertPolicy.AFTER_CONNECT.getLabel(), is("After connect to the virtual machine"));
        assertThat(RevertPolicy.BEFORE_JOB.getLabel(), is("Before every job executing on the virtual machine"));
    }
}
