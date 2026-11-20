// NEXUS privileged service: runs inside the Shizuku process (ADB privileges).
// Only fixed command ids cross this boundary — never shell text.
package ai.hybrid.commandcenter.androidbridge;

interface INexusPrivilegedService {
    /**
     * Executes one command addressed by its fixed id.
     * Returns "exit|output"; an unknown id returns an error without running anything.
     */
    String runFixedCommand(String commandId, long timeoutSeconds) = 1;

    void destroy() = 16777114;
}
