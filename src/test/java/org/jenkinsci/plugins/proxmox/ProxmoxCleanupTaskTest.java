package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;

import hudson.model.PeriodicWork;
import hudson.ExtensionList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Test for ProxmoxCleanupTask extension registration and functionality.
 */
@WithJenkins
class ProxmoxCleanupTaskTest {

    @Test
    void should_have_cleanup_task_registered_as_periodic_work(JenkinsRule r) {
        // Get all PeriodicWork extensions
        ExtensionList<PeriodicWork> periodicWorks = PeriodicWork.all();

        // Check that ProxmoxCleanupTask is registered
        boolean found = false;
        for (PeriodicWork work : periodicWorks) {
            if (work instanceof ProxmoxCleanupTask) {
                found = true;
                break;
            }
        }

        assertThat("ProxmoxCleanupTask should be registered as PeriodicWork extension", found);
    }

    @Test
    void should_have_valid_recurrence_period(JenkinsRule r) {
        // Get all PeriodicWork extensions
        ExtensionList<PeriodicWork> periodicWorks = PeriodicWork.all();

        ProxmoxCleanupTask cleanupTask = null;
        for (PeriodicWork work : periodicWorks) {
            if (work instanceof ProxmoxCleanupTask) {
                cleanupTask = (ProxmoxCleanupTask) work;
                break;
            }
        }

        assertThat("ProxmoxCleanupTask should be found", cleanupTask, notNullValue());

        // Verify recurrence period is set (should be 1 hour = 3600000 ms)
        long period = cleanupTask.getRecurrencePeriod();
        assertThat("Recurrence period should be positive", period, greaterThan(0L));
        assertThat("Recurrence period should be 1 hour (3600000ms)", period == 3600000L);
    }

    @Test
    void should_be_properly_instantiated(JenkinsRule r) {
        // Verify the cleanup task can be instantiated
        ProxmoxCleanupTask task = new ProxmoxCleanupTask();

        assertThat("ProxmoxCleanupTask should be instantiable", task, notNullValue());
        assertThat("Should be a PeriodicWork", task, instanceOf(PeriodicWork.class));
    }

    @Test
    void should_have_only_one_cleanup_task_registered(JenkinsRule r) {
        // Regression test to ensure we don't accidentally register multiple cleanup tasks
        ExtensionList<PeriodicWork> periodicWorks = PeriodicWork.all();

        long cleanupTaskCount = periodicWorks.stream()
            .filter(work -> work instanceof ProxmoxCleanupTask)
            .count();

        assertThat("Should have exactly one ProxmoxCleanupTask registered, found: " + cleanupTaskCount,
                   cleanupTaskCount == 1L);
    }

    @Test
    void should_list_all_periodic_work_for_debugging(JenkinsRule r) {
        // Debugging test to see what periodic works are registered
        ExtensionList<PeriodicWork> periodicWorks = PeriodicWork.all();

        List<String> workNames = periodicWorks.stream()
            .map(work -> work.getClass().getSimpleName())
            .collect(Collectors.toList());

        System.out.println("Registered PeriodicWork extensions: " + workNames);

        assertThat("Should have ProxmoxCleanupTask in list",
                   workNames, hasItem("ProxmoxCleanupTask"));
    }
}
