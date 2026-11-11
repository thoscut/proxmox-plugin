package org.jenkinsci.plugins.proxmox.pve2api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the argument vectors {@link Connector} hands to the QEMU guest agent.
 */
class ConnectorGuestCommandTest {

    private static final String CMD = "C:\\Windows\\System32\\cmd.exe";
    private static final String POWERSHELL = "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";

    @Test
    void unix_commands_run_through_the_shell() {
        assertThat(
                Connector.guestCommandArguments("ls -la /tmp"), contains("/bin/sh", "--", "-c", "ls", "-la", "/tmp"));
        assertThat(
                Connector.guestCommandArguments("systemctl status nginx"),
                contains("/bin/sh", "--", "-c", "systemctl", "status", "nginx"));
    }

    @Test
    void windows_commands_run_through_cmd() {
        assertThat(
                Connector.guestCommandArguments("ipconfig /all"), contains(CMD, "--", "/c", "ipconfig", "/all"));
        assertThat(
                Connector.guestCommandArguments("C:\\tools\\build.bat release"),
                contains(CMD, "--", "/c", "C:\\tools\\build.bat", "release"));
    }

    @Test
    void a_trailing_backslash_is_removed_from_windows_commands() {
        assertThat(Connector.guestCommandArguments("dir c:\\"), contains(CMD, "--", "/c", "dir", "c:"));
        assertThat(Connector.sanitizeWindowsCommand("type c:\\temp\\"), is("type c:\\temp"));
    }

    @Test
    void an_explicit_cmd_is_not_wrapped_again() {
        assertThat(Connector.guestCommandArguments("cmd /c echo hi"), contains(CMD, "--", "/c", "echo", "hi"));
        assertThat(Connector.guestCommandArguments("cmd.exe /c echo hi"), contains(CMD, "--", "/c", "echo", "hi"));
        assertThat(Connector.guestCommandArguments("cmd"), contains(CMD));
    }

    @Test
    void powershell_gets_no_argument_separator() {
        assertThat(
                Connector.guestCommandArguments("powershell -Command Get-Process"),
                contains(POWERSHELL, "-Command", "Get-Process"));
        assertThat(
                Connector.guestCommandArguments("PowerShell.exe -File C:\\run.ps1"),
                contains(POWERSHELL, "-File", "C:\\run.ps1"));
        assertThat(Connector.guestCommandArguments("powershell"), contains(POWERSHELL));
    }
}
