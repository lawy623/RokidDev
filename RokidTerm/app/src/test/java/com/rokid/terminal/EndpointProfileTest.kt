package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointProfileTest {
    private fun profile() = EndpointProfile(
        id = "cloud",
        name = "Cloud",
        host = "example.com",
        port = 22,
        user = "rokid",
        knownHost = "example.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest",
        workspace = "/srv/projects/my World",
        sessionName = "cloud-claude",
    )

    private val statusOptions =
        "tmux set-option -t cloud-claude status-left '[#{session_name}] ' && " +
            "tmux set-option -t cloud-claude status-left-length '20' && " +
            "tmux set-option -t cloud-claude status-right '%H:%M' && " +
            "tmux set-option -t cloud-claude status-right-length '5' && " +
            "tmux set-option -t cloud-claude window-status-format '#W#{?window_flags,#{window_flags}, }' && " +
            "tmux set-option -t cloud-claude window-status-current-format '#W#{?window_flags,#{window_flags}, }' && " +
            "tmux set-option -t cloud-claude window-status-separator ' ' && "

    @Test
    fun `remote command attaches without launching claude`() {
        val profile = profile()

        assertEquals(
            "(tmux has-session -t cloud-claude 2>/dev/null || " +
                "tmux new-session -d -s cloud-claude -c '/srv/projects/my World') && " +
                statusOptions +
                "exec tmux attach-session -t cloud-claude",
            profile.remoteCommand,
        )
    }

    @Test
    fun `legacy remote command launches claude in the workspace`() {
        assertEquals(
            "(tmux has-session -t cloud-claude 2>/dev/null || " +
                "tmux new-session -d -s cloud-claude -c '/srv/projects/my World' " +
                "/home/rokid/bin/rokid-claude --effort max --dangerously-skip-permissions) && " +
                statusOptions +
                "exec tmux attach-session -t cloud-claude",
            profile().legacyRemoteCommand,
        )
    }
}
