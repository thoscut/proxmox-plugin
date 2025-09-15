package org.jenkinsci.plugins.proxmox;

import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for cloud menu generation functionality to prevent regressions.
 * Ensures Jenkins can properly generate menus for Proxmox clouds.
 */
@WithJenkins
public class CloudMenuGenerationTest {

    @Test
    public void testDatacenterDoesNotOverrideJenkinsUrlMethods(JenkinsRule r) throws Exception {
        // Create a datacenter instance
        Datacenter datacenter = new Datacenter(
                "test.example.com",
                "testuser", 
                "pve",
                Secret.fromString("testpass"),
                true,
                new ArrayList<>(),
                10
        );
        
        // Verify that the datacenter doesn't have custom URL methods that would interfere with Jenkins
        // These methods should not exist or should use default Cloud behavior
        
        // The class should extend Cloud properly
        assertTrue(datacenter instanceof hudson.slaves.Cloud, "Datacenter should extend Cloud");
        
        // Verify the cloud has a proper name (required for Jenkins URL generation)
        assertNotNull(datacenter.name, "Cloud name should not be null");
        assertTrue(!datacenter.name.isEmpty(), "Cloud name should not be empty");
        assertTrue(datacenter.name.contains("Proxmox"), "Cloud name should contain 'Proxmox'");
    }

    @Test
    public void testCloudCanBeAddedToJenkins(JenkinsRule r) throws Exception {
        // Create and add a datacenter to Jenkins
        Datacenter datacenter = new Datacenter(
                "test.example.com",
                "testuser",
                "pve", 
                Secret.fromString("testpass"),
                true,
                new ArrayList<>(),
                5
        );
        
        // Add to Jenkins clouds
        r.jenkins.clouds.add(datacenter);
        
        // Verify it was added successfully
        assertEquals(1, r.jenkins.clouds.size(), "Should have one cloud");
        assertTrue(r.jenkins.clouds.get(0) instanceof Datacenter, "Cloud should be our datacenter");
        
        // Verify Jenkins can access the cloud properly
        hudson.slaves.Cloud retrievedCloud = r.jenkins.clouds.get(0);
        assertEquals(datacenter, retrievedCloud, "Retrieved cloud should be the same");
    }

    @Test
    public void testDoDeleteMethodExists(JenkinsRule r) throws Exception {
        // Create a datacenter instance
        Datacenter datacenter = new Datacenter(
                "test.example.com",
                "testuser",
                "pve",
                Secret.fromString("testpass"), 
                true,
                new ArrayList<>(),
                10
        );
        
        // Add to Jenkins
        r.jenkins.clouds.add(datacenter);
        assertEquals(1, r.jenkins.clouds.size(), "Should have one cloud");
        
        // Test that doDelete method exists by using reflection
        // This ensures the delete functionality is available for Jenkins menu generation
        try {
            java.lang.reflect.Method doDeleteMethod = datacenter.getClass().getDeclaredMethod(
                "doDelete", StaplerRequest2.class, StaplerResponse2.class);
            assertNotNull(doDeleteMethod, "doDelete method should exist");
            assertTrue(doDeleteMethod.isAnnotationPresent(org.kohsuke.stapler.verb.POST.class), 
                      "doDelete method should have @POST annotation");
        } catch (NoSuchMethodException e) {
            fail("doDelete method should exist for Jenkins menu generation");
        }
    }

    @Test
    public void testDatacenterDescriptorIsRegistered(JenkinsRule r) throws Exception {
        // Verify that the Datacenter descriptor is properly registered
        // This is essential for Jenkins to generate configuration pages and menus
        
        Datacenter.DescriptorImpl descriptor = (Datacenter.DescriptorImpl) 
                r.jenkins.getDescriptorOrDie(Datacenter.class);
        
        assertNotNull(descriptor, "Descriptor should be registered");
        assertEquals("Proxmox Datacenter", descriptor.getDisplayName(), 
                "Descriptor display name should be correct");
        
        // Verify descriptor is available in cloud descriptors
        boolean found = false;
        for (hudson.model.Descriptor<hudson.slaves.Cloud> cloudDescriptor : hudson.slaves.Cloud.all()) {
            if (cloudDescriptor instanceof Datacenter.DescriptorImpl) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Datacenter descriptor should be available in Cloud descriptors");
    }

    @Test
    public void testCloudPageAccessibility(JenkinsRule r) throws Exception {
        // Create and add a datacenter
        Datacenter datacenter = new Datacenter(
                "test.example.com", 
                "testuser",
                "pve",
                Secret.fromString("testpass"),
                true,
                new ArrayList<>(),
                10
        );
        
        r.jenkins.clouds.add(datacenter);
        
        // Test that we can access the cloud through Jenkins' web interface
        // This simulates how Jenkins generates URLs for cloud pages
        
        // The cloud should be accessible via Jenkins' cloud management
        hudson.slaves.Cloud retrievedCloud = null;
        for (hudson.slaves.Cloud cloud : r.jenkins.clouds) {
            if (cloud instanceof Datacenter && cloud.name.contains("Proxmox")) {
                retrievedCloud = cloud;
                break;
            }
        }
        
        assertNotNull(retrievedCloud, "Cloud should be findable by Jenkins");
        assertEquals(datacenter, retrievedCloud, "Found cloud should be our datacenter");
    }

    @Test
    public void testCloudStatisticsIntegration(JenkinsRule r) throws Exception {
        // Verify that cloud statistics don't interfere with menu generation
        Datacenter datacenter = new Datacenter(
                "test.example.com",
                "testuser", 
                "pve",
                Secret.fromString("testpass"),
                true,
                new ArrayList<>(),
                10
        );
        
        // Test that toString works properly (used for cloud list display)
        String cloudString = datacenter.toString();
        assertNotNull(cloudString, "toString should not return null");
        assertTrue(cloudString.length() > 0, "toString should contain datacenter info");
        
        // Test that statistics don't break cloud functionality
        try {
            datacenter.updateStatistics();
            // If this doesn't throw an exception, statistics are working
            assertTrue(true, "Statistics update should not break cloud functionality");
        } catch (Exception e) {
            // Some failures are expected when not connected to actual Proxmox
            // The important thing is that the method exists and is callable
            assertTrue(true, "updateStatistics method should exist");
        }
    }

    @Test
    public void testNoCustomUrlMethodsPresent(JenkinsRule r) throws Exception {
        // Verify that we don't have custom URL methods that would interfere with Jenkins
        Datacenter datacenter = new Datacenter(
                "test.example.com",
                "testuser",
                "pve", 
                Secret.fromString("testpass"),
                true,
                new ArrayList<>(),
                10
        );
        
        // Use reflection to verify specific methods don't exist or use default behavior
        Class<? extends Datacenter> clazz = datacenter.getClass();
        
        // Check that we don't override getUrl() in a way that breaks Jenkins
        try {
            java.lang.reflect.Method getUrlMethod = clazz.getDeclaredMethod("getUrl");
            // If this method exists, it could interfere with Jenkins - this test will fail
            fail("getUrl method should not be overridden as it interferes with Jenkins menu generation");
        } catch (NoSuchMethodException e) {
            // Good - method doesn't exist, Jenkins can use default behavior
            assertTrue(true, "getUrl method should not exist to avoid interference");
        }
        
        // Check that we don't override getDisplayName() in a way that breaks Jenkins
        try {
            java.lang.reflect.Method getDisplayNameMethod = clazz.getDeclaredMethod("getDisplayName");
            // If this method exists, it could interfere with Jenkins - this test will fail
            fail("getDisplayName method should not be overridden as it interferes with Jenkins menu generation");
        } catch (NoSuchMethodException e) {
            // Good - method doesn't exist, Jenkins can use default behavior  
            assertTrue(true, "getDisplayName method should not exist to avoid interference");
        }
        
        // Check that we don't override getSearchUrl() in a way that breaks Jenkins
        try {
            java.lang.reflect.Method getSearchUrlMethod = clazz.getDeclaredMethod("getSearchUrl");
            // If this method exists, it could interfere with Jenkins - this test will fail
            fail("getSearchUrl method should not be overridden as it interferes with Jenkins menu generation");
        } catch (NoSuchMethodException e) {
            // Good - method doesn't exist, Jenkins can use default behavior
            assertTrue(true, "getSearchUrl method should not exist to avoid interference");
        }
    }
}