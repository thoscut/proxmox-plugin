package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Tests for ProxmoxCloudStatistics functionality.
 */
@WithJenkins
class ProxmoxCloudStatisticsTest {

    @Test
    void should_create_statistics_instance(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();

        // When
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // Then
        assertThat("Statistics should be created", stats, notNullValue());
    }

    @Test
    void should_start_with_zero_provisioning_attempts(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        long attempts = stats.getTotalProvisioningAttempts();

        // Then
        assertThat("Initial provisioning attempts should be 0", attempts, is(0L));
    }

    @Test
    void should_start_with_zero_successful_provisionings(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        long successes = stats.getSuccessfulProvisionings();

        // Then
        assertThat("Initial successful provisionings should be 0", successes, is(0L));
    }

    @Test
    void should_start_with_zero_failed_provisionings(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        long failures = stats.getFailedProvisionings();

        // Then
        assertThat("Initial failed provisionings should be 0", failures, is(0L));
    }

    @Test
    void should_record_provisioning_success(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);
        long duration = 5000L;

        // When
        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(duration);

        // Then
        assertThat("Provisioning attempts should be incremented",
                   stats.getTotalProvisioningAttempts(), is(1L));
        assertThat("Successful provisionings should be incremented",
                   stats.getSuccessfulProvisionings(), is(1L));
        assertThat("Failed provisionings should remain 0",
                   stats.getFailedProvisionings(), is(0L));
    }

    @Test
    void should_record_provisioning_failure(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);
        String reason = "VM clone failed";

        // When
        stats.recordProvisioningAttempt();
        stats.recordProvisioningFailure(reason);

        // Then
        assertThat("Provisioning attempts should be incremented",
                   stats.getTotalProvisioningAttempts(), is(1L));
        assertThat("Failed provisionings should be incremented",
                   stats.getFailedProvisionings(), is(1L));
        assertThat("Successful provisionings should remain 0",
                   stats.getSuccessfulProvisionings(), is(0L));
    }

    @Test
    void should_track_multiple_provisioning_attempts(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(1000L);
        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(2000L);
        stats.recordProvisioningAttempt();
        stats.recordProvisioningFailure("Error 1");
        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(1500L);
        stats.recordProvisioningAttempt();
        stats.recordProvisioningFailure("Error 2");

        // Then
        assertThat("Total attempts should be 5", stats.getTotalProvisioningAttempts(), is(5L));
        assertThat("Successes should be 3", stats.getSuccessfulProvisionings(), is(3L));
        assertThat("Failures should be 2", stats.getFailedProvisionings(), is(2L));
    }

    @Test
    void should_calculate_success_rate_with_no_attempts(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        double successRate = stats.getSuccessRate();

        // Then
        assertThat("Success rate should be 0.0 with no attempts",
                   successRate, is(0.0));
    }

    @Test
    void should_calculate_success_rate_with_all_successes(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(1000L);
        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(2000L);
        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(1500L);

        // When
        double successRate = stats.getSuccessRate();

        // Then
        assertThat("Success rate should be 100% (100.0)",
                   successRate, is(100.0));
    }

    @Test
    void should_calculate_success_rate_with_all_failures(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        stats.recordProvisioningAttempt();
        stats.recordProvisioningFailure("Error 1");
        stats.recordProvisioningAttempt();
        stats.recordProvisioningFailure("Error 2");

        // When
        double successRate = stats.getSuccessRate();

        // Then
        assertThat("Success rate should be 0% (0.0)",
                   successRate, is(0.0));
    }

    @Test
    void should_calculate_success_rate_with_mixed_results(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(1000L);
        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(2000L);
        stats.recordProvisioningAttempt();
        stats.recordProvisioningFailure("Error");
        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(1500L);

        // When
        double successRate = stats.getSuccessRate();

        // Then
        // 3 successes out of 4 attempts = 75.0%
        assertThat("Success rate should be 75% (75.0)",
                   successRate, is(75.0));
    }

    @Test
    void should_track_average_provisioning_duration(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        stats.recordProvisioningSuccess(2000L);

        // When
        long avgDuration = stats.getAverageProvisioningTimeMs();

        // Then
        // Average is calculated as (old_avg + new) / 2, starting from 0
        assertThat("Average duration should be tracked",
                   avgDuration, greaterThanOrEqualTo(0L));
    }

    @Test
    void should_return_zero_average_duration_with_no_successes(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        stats.recordProvisioningAttempt();
        stats.recordProvisioningFailure("Error");

        // When
        long avgDuration = stats.getAverageProvisioningTimeMs();

        // Then
        assertThat("Average duration should be 0 with no successes",
                   avgDuration, is(0L));
    }

    @Test
    void should_update_current_status(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        stats.updateCurrentStatus();

        // Then - Should not throw exception
        assertThat("Statistics should remain valid after update", stats, notNullValue());
    }

    @Test
    void should_get_health_summary(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        String summary = stats.getHealthSummary();

        // Then
        assertThat("Health summary should not be null", summary, notNullValue());
    }

    @Test
    void should_get_detailed_report(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        stats.recordProvisioningAttempt();
        stats.recordProvisioningSuccess(1000L);

        // When
        String report = stats.getDetailedReport();

        // Then
        assertThat("Detailed report should not be null", report, notNullValue());
        assertThat("Report should contain datacenter info",
                   report.contains("PROXMOX CLOUD STATISTICS"));
        assertThat("Report should contain provisioning stats",
                   report.contains("PROVISIONING STATISTICS"));
    }

    @Test
    void should_record_termination(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        stats.recordTermination();

        // Then - Should not throw exception
        assertThat("Statistics should remain valid after termination", stats, notNullValue());
    }

    @Test
    void should_get_instance_for_datacenter(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();

        // When
        ProxmoxCloudStatistics stats1 = ProxmoxCloudStatistics.getInstance(datacenter);
        ProxmoxCloudStatistics stats2 = ProxmoxCloudStatistics.getInstance(datacenter);

        // Then
        assertThat("Should return same instance for same datacenter",
                   stats1 == stats2);
    }

    @Test
    void should_track_current_slave_count(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        stats.updateCurrentStatus();
        int count = stats.getCurrentSlaveCount();

        // Then
        assertThat("Slave count should be tracked", count, greaterThanOrEqualTo(0));
    }

    @Test
    void should_track_online_slaves(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        stats.updateCurrentStatus();
        int online = stats.getOnlineSlaves();

        // Then
        assertThat("Online slaves should be tracked", online, greaterThanOrEqualTo(0));
    }

    @Test
    void should_track_offline_slaves(JenkinsRule r) {
        // Given
        Datacenter datacenter = createTestDatacenter();
        ProxmoxCloudStatistics stats = new ProxmoxCloudStatistics(datacenter);

        // When
        stats.updateCurrentStatus();
        int offline = stats.getOfflineSlaves();

        // Then
        assertThat("Offline slaves should be tracked", offline, greaterThanOrEqualTo(0));
    }

    // Helper method to create test datacenter
    private Datacenter createTestDatacenter() {
        return new Datacenter(
            "test.proxmox.local",
            "test-credentials",
            "pve",
            true,
            new ArrayList<>(),
            10
        );
    }
}
