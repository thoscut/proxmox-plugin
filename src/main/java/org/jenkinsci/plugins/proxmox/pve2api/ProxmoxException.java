package org.jenkinsci.plugins.proxmox.pve2api;

import java.io.IOException;

/**
 * Signals that a request to the Proxmox VE API failed: the host could not be reached,
 * the server answered with an error status, the response could not be parsed or a
 * task did not complete in time.
 */
public class ProxmoxException extends IOException {

    private static final long serialVersionUID = 1L;

    public ProxmoxException(String message) {
        super(message);
    }

    public ProxmoxException(String message, Throwable cause) {
        super(message, cause);
    }
}
