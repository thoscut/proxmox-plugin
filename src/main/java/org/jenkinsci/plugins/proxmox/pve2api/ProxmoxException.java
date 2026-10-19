package org.jenkinsci.plugins.proxmox.pve2api;

import java.io.IOException;

/**
 * Signals that a request to the Proxmox VE API failed (network error, HTTP error status or
 * unexpected response body).
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
