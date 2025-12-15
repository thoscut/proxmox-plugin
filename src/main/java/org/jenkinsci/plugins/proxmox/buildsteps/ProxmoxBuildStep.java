package org.jenkinsci.plugins.proxmox.buildsteps;

import hudson.AbortException;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.slaves.Cloud;
import hudson.tasks.BuildStep;
import hudson.tasks.Builder;
import hudson.util.ListBoxModel;

import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.login.LoginException;

import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.Datacenter;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.kohsuke.stapler.QueryParameter;

public abstract class ProxmoxBuildStep extends Builder implements BuildStep {
    
    private static final Logger LOGGER = Logger.getLogger(ProxmoxBuildStep.class.getName());
    
    protected final String datacenterDescription;
    protected final String datacenterNode;
    protected final String vmId;
    
    public ProxmoxBuildStep(String datacenterDescription, String datacenterNode, String vmId) {
        this.datacenterDescription = datacenterDescription;
        this.datacenterNode = datacenterNode;
        this.vmId = vmId;
    }
    
    protected Datacenter getDatacenter() throws AbortException {
        if (datacenterDescription == null || datacenterDescription.isEmpty()) {
            throw new AbortException("Datacenter not specified");
        }
        
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof Datacenter) {
                Datacenter datacenter = (Datacenter) cloud;
                if (datacenter.getDatacenterDescription().equals(datacenterDescription)) {
                    return datacenter;
                }
            }
        }
        
        throw new AbortException("Datacenter not found: " + datacenterDescription);
    }
    
    protected Connector getProxmoxConnector() throws AbortException {
        Datacenter datacenter = getDatacenter();
        return datacenter.proxmoxInstance();
    }
    
    protected void logInfo(BuildListener listener, String message) {
        listener.getLogger().println("[Proxmox] " + message);
        LOGGER.log(Level.INFO, message);
    }
    
    protected void logError(BuildListener listener, String message, Exception e) {
        listener.getLogger().println("[Proxmox ERROR] " + message);
        if (e != null) {
            listener.getLogger().println("[Proxmox ERROR] " + e.getMessage());
            LOGGER.log(Level.SEVERE, message, e);
        }
    }
    
    protected Integer parseVmId() throws AbortException {
        try {
            return Integer.parseInt(vmId);
        } catch (NumberFormatException e) {
            throw new AbortException("Invalid VM ID: " + vmId);
        }
    }
    
    public String getDatacenterDescription() {
        return datacenterDescription;
    }
    
    public String getDatacenterNode() {
        return datacenterNode;
    }
    
    public String getVmId() {
        return vmId;
    }
    
    public abstract static class ProxmoxBuildStepDescriptor extends Descriptor<Builder> {
        
        public ListBoxModel doFillDatacenterDescriptionItems() {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            
            for (Cloud cloud : Jenkins.get().clouds) {
                if (cloud instanceof Datacenter) {
                    Datacenter datacenter = (Datacenter) cloud;
                    items.add(datacenter.getDatacenterDescription());
                }
            }
            return items;
        }
        
        public ListBoxModel doFillDatacenterNodeItems(@QueryParameter String datacenterDescription) {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            
            if (datacenterDescription != null && !datacenterDescription.isEmpty()) {
                for (Cloud cloud : Jenkins.get().clouds) {
                    if (cloud instanceof Datacenter) {
                        Datacenter datacenter = (Datacenter) cloud;
                        if (datacenter.getDatacenterDescription().equals(datacenterDescription)) {
                            for (String node : datacenter.getNodes()) {
                                items.add(node);
                            }
                            break;
                        }
                    }
                }
            }
            return items;
        }
        
        public ListBoxModel doFillVmIdItems(@QueryParameter String datacenterDescription, 
                                           @QueryParameter String datacenterNode) {
            ListBoxModel items = new ListBoxModel();
            items.add("[Select]", "");
            
            if (datacenterDescription != null && !datacenterDescription.isEmpty() &&
                datacenterNode != null && !datacenterNode.isEmpty()) {
                for (Cloud cloud : Jenkins.get().clouds) {
                    if (cloud instanceof Datacenter) {
                        Datacenter datacenter = (Datacenter) cloud;
                        if (datacenter.getDatacenterDescription().equals(datacenterDescription)) {
                            HashMap<String, Integer> machines = datacenter.getQemuMachines(datacenterNode);
                            for (HashMap.Entry<String, Integer> entry : machines.entrySet()) {
                                items.add(entry.getKey() + " (" + entry.getValue() + ")", entry.getValue().toString());
                            }
                            break;
                        }
                    }
                }
            }
            return items;
        }
    }
}