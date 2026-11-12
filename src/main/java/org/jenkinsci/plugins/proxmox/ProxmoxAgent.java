package org.jenkinsci.plugins.proxmox;

/**
 * A Jenkins agent that runs in a virtual machine of a Proxmox datacenter: either a {@link
 * VirtualMachineSlave}, which was bound to an existing virtual machine by hand, or a {@link
 * ProvisionedVirtualMachineSlave}, for which the cloud cloned a virtual machine.
 */
public interface ProxmoxAgent {

    String getNodeName();

    /** @return the description of the {@link Datacenter} the virtual machine belongs to. */
    String getDatacenterDescription();

    /** @return the Proxmox node the virtual machine runs on. */
    String getDatacenterNode();

    Integer getVirtualMachineId();
}
