package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import hudson.slaves.ComputerLauncher;
import hudson.slaves.JNLPLauncher;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.proxmox.VirtualMachineLauncher.RevertPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link VirtualMachineLauncher}.
 * Validates field persistence, null safety, and enum behavior.
 */
class VirtualMachineLauncherTest {

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

    @Test
    void negative_waiting_time_is_clamped() {
        VirtualMachineLauncher launcher =
                new VirtualMachineLauncher(null, "dc", "node", 100, "snap", true, -5, RevertPolicy.AFTER_CONNECT);
        assertThat(launcher.getWaitingTimeSecs(), is(0));
    }

    @Test
    void null_delegate_defaults_to_inbound_launcher() {
        VirtualMachineLauncher launcher =
                new VirtualMachineLauncher(null, "dc", "node", 100, "snap", true, 0, RevertPolicy.AFTER_CONNECT);
        assertThat(launcher.getLauncher(), instanceOf(JNLPLauncher.class));
        assertThat(launcher.isLaunchSupported(), is(true));
    }

    @Test
    void legacy_configuration_is_migrated_on_read() throws Exception {
        VirtualMachineLauncher legacy =
                new VirtualMachineLauncher(null, "dc", "node", 100, "snap", true, 0, RevertPolicy.AFTER_CONNECT);
        ComputerLauncher delegate = new JNLPLauncher();
        setField(legacy, "delegate", delegate);
        setField(legacy, "WAIT_TIME_MS", 30_000);

        Method readResolve = VirtualMachineLauncher.class.getDeclaredMethod("readResolve");
        readResolve.setAccessible(true);
        VirtualMachineLauncher migrated = (VirtualMachineLauncher) readResolve.invoke(legacy);

        assertThat(migrated.getLauncher(), is(delegate));
        assertThat(migrated.getWaitingTimeSecs(), is(30));
        assertThat(migrated.getVirtualMachineId(), is(100));
        assertThat(migrated.getSnapshotName(), is("snap"));
    }

    @Test
    void task_status_description_is_null_safe() {
        assertThat(VirtualMachineLauncher.describe(null), is("no status"));
        assertThat(
                VirtualMachineLauncher.describe(new JSONObject("{\"status\":\"stopped\",\"exitstatus\":\"OK\"}")),
                is("status=stopped, exitstatus=OK"));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
