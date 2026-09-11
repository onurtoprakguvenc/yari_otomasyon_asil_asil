package org.example.yari;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import org.example.yari.executor.BacklogExecutorService;
import org.example.yari.model.OutputSchemaTemplate;
import org.example.yari.model.OutputSchemaTemplate.FieldSpec;
import org.example.yari.model.OutputSchemaTemplate.FieldType;
import org.example.yari.model.OutputSchemaTemplate.OutputFormat;
import org.example.yari.schema.SchemaRegistry;
import org.example.yari.settings.YariSettings;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Runs when the project opens. Registers built-in output schemas
 * and optionally starts the executor.
 */
public class YariStartupActivity implements StartupActivity.DumbAware {

    private static final Logger LOG = Logger.getInstance(YariStartupActivity.class);

    @Override
    public void runActivity(@NotNull Project project) {
        LOG.info("Yarı Otomasyon plugin initializing for project: " + project.getName());

        registerDefaultSchemas();

        YariSettings.State settings = YariSettings.getInstance().getState();
        if (settings.autoStartOnStartup) {
            project.getService(BacklogExecutorService.class).start();
            LOG.info("Executor auto-started.");
        }
    }

    private void registerDefaultSchemas() {
        SchemaRegistry registry = SchemaRegistry.getInstance();

        // JSON analysis schema
        registry.register(new OutputSchemaTemplate(
                "json-analysis",
                "JSON Analysis Output",
                "Standard JSON output for analysis tasks",
                List.of(
                        new FieldSpec("summary", FieldType.STRING, true, "Brief summary of findings", null),
                        new FieldSpec("findings", FieldType.ARRAY, true, "List of finding objects", null),
                        new FieldSpec("recommendations", FieldType.ARRAY, false, "List of recommendations", null),
                        new FieldSpec("confidence", FieldType.NUMBER, false, "Confidence score 0-1", null)
                ),
                OutputFormat.JSON
        ));

        // Markdown report schema
        registry.register(new OutputSchemaTemplate(
                "markdown-report",
                "Markdown Report",
                "Structured markdown report with required sections",
                List.of(
                        new FieldSpec("Summary", FieldType.STRING, true, "Summary section header", null),
                        new FieldSpec("Details", FieldType.STRING, true, "Details section header", null),
                        new FieldSpec("Conclusion", FieldType.STRING, false, "Conclusion section header", null)
                ),
                OutputFormat.MARKDOWN
        ));

        // Code output schema
        registry.register(new OutputSchemaTemplate(
                "code-output",
                "Code Output",
                "Raw code output (fences stripped)",
                List.of(),
                OutputFormat.CODE
        ));

        LOG.info("Registered " + registry.size() + " default output schemas.");
    }
}
