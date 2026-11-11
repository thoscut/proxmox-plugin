package org.jenkinsci.plugins.proxmox.buildsteps;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Simple unit tests for RunCommand build step.
 * These tests don't require Jenkins test harness and focus on basic functionality.
 */
class RunCommandSimpleTest {

    @Test
    void testConstructor() {
        // Test basic constructor
        RunCommand runCommand = new RunCommand(
            "test-datacenter",
            "test-node",
            "123",
            "echo 'Hello World'",
            300,
            true,
            true
        );

        assertNotNull(runCommand);
        assertEquals("test-datacenter", runCommand.getDatacenterDescription());
        assertEquals("test-node", runCommand.getDatacenterNode());
        assertEquals("123", runCommand.getVmId());
        assertEquals("echo 'Hello World'", runCommand.getCommand());
        assertEquals(300, runCommand.getTimeoutSeconds());
        assertTrue(runCommand.getWaitForCompletion());
        assertTrue(runCommand.getFailOnError());
    }

    @Test
    void testConstructorWithTimeout() {
        // Test constructor with zero timeout (should default to 300)
        RunCommand runCommand = new RunCommand(
            "test-datacenter",
            "test-node",
            "123",
            "ls -la",
            0,  // Should default to 300
            false,
            false
        );

        assertEquals(300, runCommand.getTimeoutSeconds()); // Should be defaulted
        assertFalse(runCommand.getWaitForCompletion());
        assertFalse(runCommand.getFailOnError());
    }

    @Test
    void testGetters() {
        RunCommand runCommand = new RunCommand(
            "datacenter-desc",
            "node-name",
            "456",
            "systemctl status nginx",
            600,
            true,
            false
        );

        assertEquals("datacenter-desc", runCommand.getDatacenterDescription());
        assertEquals("node-name", runCommand.getDatacenterNode());
        assertEquals("456", runCommand.getVmId());
        assertEquals("systemctl status nginx", runCommand.getCommand());
        assertEquals(600, runCommand.getTimeoutSeconds());
        assertTrue(runCommand.getWaitForCompletion());
        assertFalse(runCommand.getFailOnError());
    }

    @Test
    void testDescriptor() {
        RunCommand.DescriptorImpl descriptor = new RunCommand.DescriptorImpl();

        assertNotNull(descriptor);
        assertEquals("Run Command on Proxmox VM (Guest Agent)", descriptor.getDisplayName());
    }
}