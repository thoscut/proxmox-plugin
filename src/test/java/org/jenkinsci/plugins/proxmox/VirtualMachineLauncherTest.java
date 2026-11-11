package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertThat(RevertPolicy.values().length, is(4));
        assertThat(RevertPolicy.AFTER_CONNECT, is(notNullValue()));
        assertThat(RevertPolicy.BEFORE_JOB, is(notNullValue()));
        assertThat(RevertPolicy.AFTER_CONNECT.getLabel(), is("After connect to the virtual machine"));
        assertThat(RevertPolicy.BEFORE_JOB.getLabel(), is("Before every job executing on the virtual machine"));
        assertThat(RevertPolicy.AFTER_JOB.getLabel(), is("After every job executing on the virtual machine"));
        assertThat(RevertPolicy.NEVER.getLabel(), is("Never revert"));
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

    @Test
    void task_status_description_tolerates_missing_fields() {
        assertThat(
                VirtualMachineLauncher.describe(new JSONObject("{\"status\":\"running\"}")),
                is("status=running, exitstatus="));
        assertThat(VirtualMachineLauncher.describe(new JSONObject("{}")), is("status=, exitstatus="));
    }

    @Test
    void read_resolve_keeps_already_migrated_instances() throws Exception {
        VirtualMachineLauncher launcher =
                new VirtualMachineLauncher(null, "dc", "node", 100, "snap", true, 7, RevertPolicy.BEFORE_JOB);
        Method readResolve = VirtualMachineLauncher.class.getDeclaredMethod("readResolve");
        readResolve.setAccessible(true);
        assertThat(readResolve.invoke(launcher), is(sameInstance(launcher)));
    }

    @Test
    void legacy_migration_keeps_every_setting_and_rounds_the_wait_down() throws Exception {
        VirtualMachineLauncher legacy =
                new VirtualMachineLauncher(null, "dc", "node", 100, "snap", false, 0, RevertPolicy.BEFORE_JOB);
        ComputerLauncher delegate = new JNLPLauncher();
        setField(legacy, "delegate", delegate);
        setField(legacy, "WAIT_TIME_MS", 1500);

        Method readResolve = VirtualMachineLauncher.class.getDeclaredMethod("readResolve");
        readResolve.setAccessible(true);
        VirtualMachineLauncher migrated = (VirtualMachineLauncher) readResolve.invoke(legacy);

        assertThat(migrated, is(not(sameInstance(legacy))));
        assertThat(migrated.getLauncher(), is(sameInstance(delegate)));
        assertThat(migrated.getWaitingTimeSecs(), is(1));
        assertThat(migrated.getDatacenterDescription(), is("dc"));
        assertThat(migrated.getDatacenterNode(), is("node"));
        assertThat(migrated.getStartVM(), is(false));
        assertThat(migrated.getRevertPolicy(), is(RevertPolicy.BEFORE_JOB));
    }

    @Test
    void legacy_configurations_without_policy_revert_after_connect() {
        VirtualMachineLauncher launcher = new VirtualMachineLauncher(null, "dc", "node", 100, "snap", true, 0, null);
        assertThat(launcher.getRevertPolicy(), is((RevertPolicy) null));
        assertThat(launcher.effectiveRevertPolicy(), is(RevertPolicy.AFTER_CONNECT));
        VirtualMachineLauncher beforeJob =
                new VirtualMachineLauncher(null, "dc", "node", 100, "snap", true, 0, RevertPolicy.BEFORE_JOB);
        assertThat(beforeJob.effectiveRevertPolicy(), is(RevertPolicy.BEFORE_JOB));
    }

    @Test
    void non_inbound_delegate_is_kept_and_decides_launch_support() {
        ComputerLauncher custom = new ComputerLauncher() {
            @Override
            public boolean isLaunchSupported() {
                return false;
            }
        };
        VirtualMachineLauncher launcher =
                new VirtualMachineLauncher(custom, "dc", "node", 100, "snap", true, 0, RevertPolicy.AFTER_CONNECT);
        assertThat(launcher.getLauncher(), is(sameInstance(custom)));
        assertThat(launcher.isLaunchSupported(), is(false));
    }

    @Test
    void find_datacenter_reports_what_is_missing() {
        VirtualMachineLauncher noVm =
                new VirtualMachineLauncher(null, "admin@pam - host", "node", null, "snap", true, 0, null);
        IllegalStateException e1 = assertThrows(IllegalStateException.class, noVm::findDatacenterInstance);
        assertThat(e1.getMessage(), containsString("No virtual machine"));

        VirtualMachineLauncher noJenkins =
                new VirtualMachineLauncher(null, "admin@pam - host", "node", 100, "snap", true, 0, null);
        IllegalStateException e2 = assertThrows(IllegalStateException.class, noJenkins::findDatacenterInstance);
        assertThat(e2.getMessage(), containsString("admin@pam - host"));
        assertThat(e2.getMessage(), containsString("100"));
    }
}
