package com.rokid.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class EndpointProfileTest {
    @Test
    fun `remote command resumes Claude and applies compact tmux status`() {
        val profile = EndpointProfile(
            id = "cloud",
            name = "Cloud",
            host = "example.com",
            port = 22,
            user = "rokid",
            knownHost = "example.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest",
            workspace = "/srv/projects/my World",
            sessionName = "cloud-claude",
        )

        assertEquals(
            "(tmux has-session -t cloud-claude 2>/dev/null || " +
                "tmux new-session -d -s cloud-claude -c '/srv/projects/my World' " +
                "/home/rokid/bin/rokid-claude --effort max --dangerously-skip-permissions) && " +
                "tmux set-option -t cloud-claude status-left '[#{session_name}] ' && " +
                "tmux set-option -t cloud-claude status-left-length '20' && " +
                "tmux set-option -t cloud-claude status-right '%H:%M' && " +
                "tmux set-option -t cloud-claude status-right-length '5' && " +
                "tmux set-option -t cloud-claude window-status-format '#W#{?window_flags,#{window_flags}, }' && " +
                "tmux set-option -t cloud-claude window-status-current-format '#W#{?window_flags,#{window_flags}, }' && " +
                "tmux set-option -t cloud-claude window-status-separator ' ' && " +
                "exec tmux attach-session -t cloud-claude",
            profile.remoteCommand,
        )
    }
}
