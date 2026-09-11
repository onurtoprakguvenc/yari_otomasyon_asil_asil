package org.example.yari.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent application-level settings for the Yarı Otomasyon plugin.
 */
@State(name = "YariOtomasyonSettings", storages = @Storage("yari_otomasyon.xml"))
public class YariSettings implements PersistentStateComponent<YariSettings.State> {

    private State state = new State();

    public static YariSettings getInstance() {
        return ApplicationManager.getApplication().getService(YariSettings.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public static class State {
        // Provider configuration
        public String apiProvider = "gemini"; // gemini, anthropic, openai, cli
        public String apiKey = "";
        public String apiEndpoint = "https://generativelanguage.googleapis.com/v1beta/";
        public String modelId = "gemini-2.5-flash";
        public String cliCommand = "";

        // Rate limiting
        public int maxConcurrentTasks = 1;
        public int maxRetriesPerTask = 3;
        public long initialBackoffMs = 2000;
        public double backoffMultiplier = 2.0;
        public long maxBackoffMs = 120_000;
        public int requestsPerMinuteLimit = 20;

        // Checkpoint configuration
        public boolean alwaysRequireApproval = true; // Hardcoded override per spec
        public long approvalTimeoutMinutes = 1440; // 24 hours default
        public boolean notifyOnApprovalNeeded = true;

        // Output
        public String reportOutputDir = "";
        public String artifactOutputDir = "";

        // Queue behavior
        public boolean autoStartOnStartup = false;
        public boolean pauseOnIdeFocusLost = false;
    }
}
