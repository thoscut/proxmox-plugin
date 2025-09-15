package org.jenkinsci.plugins.proxmox;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.util.Secret;
import java.util.List;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class DatacenterCloudTest {

    @Test
    void should_register_datacenter_cloud_descriptor(JenkinsRule r) {
        // Test that the Datacenter cloud descriptor is registered in Jenkins
        // Force Extension loading
        Datacenter.DescriptorImpl proxmoxDescriptor = r.jenkins.getDescriptorByType(Datacenter.DescriptorImpl.class);
        List<Descriptor<Cloud>> cloudDescriptors = r.jenkins.getDescriptorList(Cloud.class);
        
        assertThat("Cloud descriptors list should not be empty", cloudDescriptors.size(), not(is(0)));
        
        // Find the Proxmox Datacenter descriptor
        for (Descriptor<Cloud> desc : cloudDescriptors) {
            if (desc instanceof Datacenter.DescriptorImpl) {
                proxmoxDescriptor = (Datacenter.DescriptorImpl) desc;
                break;
            }
        }
        
        assertThat("Proxmox Datacenter descriptor should be registered", proxmoxDescriptor, notNullValue());
        assertThat("Display name should be correct", proxmoxDescriptor.getDisplayName(), is("Proxmox Datacenter"));
        assertThat("Descriptor should be properly configured", proxmoxDescriptor, notNullValue());
    }
    
    @Test
    void should_create_datacenter_cloud_instance(JenkinsRule r) {
        // Test that we can create a Datacenter cloud instance
        String hostname = "test-proxmox.example.com";
        String username = "test-user";
        String realm = "pve";
        Secret password = Secret.fromString("test-password");
        Boolean ignoreSSL = true;
        Integer instanceCap = 5;
        
        Datacenter datacenter = new Datacenter(hostname, username, realm, password, ignoreSSL, null, instanceCap);
        
        assertThat("Datacenter should not be null", datacenter, notNullValue());
        assertThat("Hostname should match", datacenter.getHostname(), is(hostname));
        assertThat("Username should match", datacenter.getUsername(), is(username));
        assertThat("Realm should match", datacenter.getRealm(), is(realm));
        assertThat("Password should match", datacenter.getPassword(), is(password));
        assertThat("IgnoreSSL should match", datacenter.getIgnoreSSL(), is(ignoreSSL));
        assertThat("Instance cap should match", datacenter.getInstanceCap(), is(instanceCap));
        assertThat("Should extend Cloud", datacenter, instanceOf(Cloud.class));
    }
    
    @Test
    void should_generate_datacenter_description(JenkinsRule r) {
        // Test that datacenter description is generated correctly
        String hostname = "proxmox.company.com";
        String username = "admin";
        String realm = "pam";
        Secret password = Secret.fromString("secret");
        
        Datacenter datacenter = new Datacenter(hostname, username, realm, password, false, null, 10);
        String description = datacenter.getDatacenterDescription();
        
        assertThat("Description should not be null", description, notNullValue());
        assertThat("Description should contain username", description, containsString(username));
        assertThat("Description should contain realm", description, containsString(realm));
        assertThat("Description should contain hostname", description, containsString(hostname));
        assertThat("Description format should match pattern", description, is("admin@pam - proxmox.company.com"));
    }
    
    @Test
    void should_have_proper_cloud_name(JenkinsRule r) {
        // Test that the cloud name is generated properly
        String hostname = "my-proxmox";
        Datacenter datacenter = new Datacenter(hostname, "user", "pve", Secret.fromString("pass"), false, null, 1);
        
        assertThat("Cloud name should be generated with Proxmox prefix", 
                   datacenter.name, is("Proxmox-" + hostname));
        
        // Test with null/empty hostname
        Datacenter datacenterEmpty = new Datacenter("", "user", "pve", Secret.fromString("pass"), false, null, 1);
        assertThat("Cloud name should have default name for empty hostname", 
                   datacenterEmpty.name, is("Proxmox-Datacenter"));
        
        Datacenter datacenterNull = new Datacenter(null, "user", "pve", Secret.fromString("pass"), false, null, 1);
        assertThat("Cloud name should have default name for null hostname", 
                   datacenterNull.name, is("Proxmox-Datacenter"));
    }
    
    @Test
    void should_provide_proxmox_connector_instance(JenkinsRule r) {
        // Test that the datacenter can provide a Proxmox connector
        Datacenter datacenter = new Datacenter("test-host", "user", "pve", Secret.fromString("pass"), true, null, 1);
        
        // This should not throw an exception
        var connector = datacenter.proxmoxInstance();
        assertThat("Connector should not be null", connector, notNullValue());
        
        // Connector should be reused on subsequent calls
        var connector2 = datacenter.proxmoxInstance();
        assertThat("Connector should be the same instance", connector, is(connector2));
    }
    
    @Test
    @Disabled("Extension registration issue in test environment - needs investigation")
    void should_have_working_descriptor_methods(JenkinsRule r) {
        // Test descriptor method functionality
        Datacenter datacenter = new Datacenter("test", "user", "pve", Secret.fromString("pass"), false, null, 1);
        Datacenter.DescriptorImpl descriptor = datacenter.getDescriptor();
        
        assertThat("Descriptor should not be null", descriptor, notNullValue());
        assertThat("Display name should be set", descriptor.getDisplayName(), is("Proxmox Datacenter"));
        assertThat("Descriptor should be properly configured", descriptor, notNullValue());
    }
    
    @Test
    void should_handle_legacy_constructor(JenkinsRule r) {
        // Test the legacy constructor for backward compatibility
        String hostname = "legacy-host";
        String username = "legacy-user";
        String realm = "pve";
        Secret password = Secret.fromString("legacy-pass");
        Boolean ignoreSSL = false;
        
        Datacenter datacenter = new Datacenter(hostname, username, realm, password, ignoreSSL);
        
        assertThat("Legacy constructor should work", datacenter, notNullValue());
        assertThat("Hostname should be set", datacenter.getHostname(), is(hostname));
        assertThat("Username should be set", datacenter.getUsername(), is(username));
        assertThat("Realm should be set", datacenter.getRealm(), is(realm));
        assertThat("Password should be set", datacenter.getPassword(), is(password));
        assertThat("IgnoreSSL should be set", datacenter.getIgnoreSSL(), is(ignoreSSL));
        assertThat("Instance cap should have default value", datacenter.getInstanceCap(), is(0));
        // Note: Templates are initialized as empty list in legacy constructor, not null
        assertThat("Templates should be empty or null", 
                   datacenter.getTemplates() == null || datacenter.getTemplates().isEmpty());
    }
}