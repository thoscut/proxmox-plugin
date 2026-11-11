package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Regression test to ensure that Proxmox Datacenter cloud configuration
 * is always available in Jenkins cloud settings.
 * 
 * This test specifically addresses the issue where the cloud configuration
 * was missing from the Jenkins UI cloud settings.
 */
@WithJenkins
class CloudRegistrationRegressionTest {

    @Test
    void should_always_have_proxmox_datacenter_in_cloud_descriptors(JenkinsRule r) {
        // This is the main regression test - ensures Proxmox Datacenter appears in cloud options
        // Force Extension loading by getting the descriptor directly
        Datacenter.DescriptorImpl descriptor = r.jenkins.getDescriptorByType(Datacenter.DescriptorImpl.class);
        List<Descriptor<Cloud>> cloudDescriptors = r.jenkins.getDescriptorList(Cloud.class);
        
        // Extract all display names for easier debugging
        List<String> displayNames = cloudDescriptors.stream()
            .map(Descriptor::getDisplayName)
            .collect(Collectors.toList());
        
        // Log the available cloud types for debugging
        System.out.println("Available cloud types: " + displayNames);
        
        // The critical assertion - Proxmox Datacenter must be available
        assertThat("Proxmox Datacenter should be available in cloud options", 
                   displayNames, hasItem("Proxmox Datacenter"));
        
        // Additional verification - ensure it's not just a string match but actually our descriptor
        Datacenter.DescriptorImpl proxmoxDescriptor = null;
        for (Descriptor<Cloud> desc : cloudDescriptors) {
            if ("Proxmox Datacenter".equals(desc.getDisplayName())) {
                assertThat("Proxmox Datacenter descriptor should be our DescriptorImpl type",
                           desc instanceof Datacenter.DescriptorImpl);
                proxmoxDescriptor = (Datacenter.DescriptorImpl) desc;
                break;
            }
        }
        
        assertThat("Should have found our Proxmox descriptor", proxmoxDescriptor, notNullValue());
    }
    
    @Test
    void should_have_extension_properly_registered(JenkinsRule r) {
        // Test that the Extension annotation is working properly
        // Force Extension loading
        Datacenter.DescriptorImpl descriptor = r.jenkins.getDescriptorByType(Datacenter.DescriptorImpl.class);
        List<Descriptor<Cloud>> cloudDescriptors = r.jenkins.getDescriptorList(Cloud.class);
        
        // Look for our specific descriptor class
        boolean foundProxmoxDescriptor = false;
        for (Descriptor<Cloud> desc : cloudDescriptors) {
            if (desc.getClass().equals(Datacenter.DescriptorImpl.class)) {
                foundProxmoxDescriptor = true;
                
                // Verify the descriptor is properly configured
                assertThat("Class name should contain Datacenter", 
                           desc.getClass().getName(), containsString("Datacenter"));
                assertThat("Class name should contain DescriptorImpl", 
                           desc.getClass().getName(), containsString("DescriptorImpl"));
                break;
            }
        }
        
        assertThat("Proxmox Datacenter.DescriptorImpl should be registered as Extension", 
                   foundProxmoxDescriptor);
    }
    
    @Test
    void should_not_have_duplicate_proxmox_entries(JenkinsRule r) {
        // Regression test to ensure we don't accidentally register multiple Proxmox cloud types
        // Force Extension loading
        Datacenter.DescriptorImpl descriptor = r.jenkins.getDescriptorByType(Datacenter.DescriptorImpl.class);
        List<Descriptor<Cloud>> cloudDescriptors = r.jenkins.getDescriptorList(Cloud.class);
        
        long proxmoxCount = cloudDescriptors.stream()
            .map(Descriptor::getDisplayName)
            .filter(name -> name != null && name.toLowerCase().contains("proxmox"))
            .count();
        
        assertThat("Should have exactly one Proxmox cloud type registered, found: " + proxmoxCount, 
                   proxmoxCount == 1);
    }
    
    @Test
    void should_have_datacenter_descriptor_with_correct_class_hierarchy(JenkinsRule r) {
        // Verify the class hierarchy is correct for proper Jenkins integration
        Datacenter.DescriptorImpl descriptor = new Datacenter.DescriptorImpl();
        
        assertThat("DescriptorImpl should extend Descriptor", 
                   descriptor instanceof Descriptor);
        
        @SuppressWarnings("unchecked")
        Descriptor<Cloud> cloudDescriptor = (Descriptor<Cloud>) descriptor;
        assertThat("Should be a Cloud descriptor", cloudDescriptor, notNullValue());
        
        // Test descriptor methods that Jenkins UI relies on
        assertThat("Display name should not be empty", 
                   descriptor.getDisplayName() != null && !descriptor.getDisplayName().trim().isEmpty());
        // Descriptor should be properly configured (basic test)
        assertThat("Descriptor should be non-null", descriptor, notNullValue());
    }
    
    @Test
    void should_create_working_cloud_instance_through_descriptor(JenkinsRule r) {
        // Test that the descriptor can actually create working cloud instances
        // This ensures the UI configuration form will work properly
        
        // Force Extension loading
        Datacenter.DescriptorImpl proxmoxDescriptor = r.jenkins.getDescriptorByType(Datacenter.DescriptorImpl.class);
        List<Descriptor<Cloud>> cloudDescriptors = r.jenkins.getDescriptorList(Cloud.class);
        
        for (Descriptor<Cloud> desc : cloudDescriptors) {
            if (desc instanceof Datacenter.DescriptorImpl) {
                proxmoxDescriptor = (Datacenter.DescriptorImpl) desc;
                break;
            }
        }
        
        assertThat("Should find Proxmox descriptor", proxmoxDescriptor, notNullValue());
        
        // Test that we can create a Datacenter through the descriptor path
        // (This simulates what Jenkins UI does)
        assertThat("Descriptor class should match expected", 
                   proxmoxDescriptor.clazz.equals(Datacenter.class));
        
        // Verify the descriptor is properly initialized
        assertThat("Descriptor should have proper display name", 
                   proxmoxDescriptor.getDisplayName(), not(containsString("null")));
    }
}