package org.example.yari.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.ui.components.*;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

/**
 * Settings panel accessible via Settings → Tools → Yarı Otomasyon.
 */
public class YariSettingsConfigurable implements Configurable {

    private JPanel mainPanel;
    private JComboBox<String> providerCombo;
    private JBPasswordField apiKeyField;
    private JBTextField apiEndpointField;
    private JBTextField modelIdField;
    private JBTextField cliCommandField;
    private JSpinner maxConcurrentSpinner;
    private JSpinner maxRetriesSpinner;
    private JSpinner backoffMsSpinner;
    private JSpinner rpmLimitSpinner;
    private JSpinner approvalTimeoutSpinner;
    private JBCheckBox notifyOnApprovalCheckbox;
    private JBTextField reportOutputDirField;
    private JBTextField artifactOutputDirField;

    @Nls(capitalization = Nls.Capitalization.Title)
    @Override
    public String getDisplayName() {
        return "Yarı Otomasyon";
    }

    @Override
    public @Nullable JComponent createComponent() {
        YariSettings.State s = YariSettings.getInstance().getState();

        providerCombo = new JComboBox<>(new String[]{"gemini", "anthropic", "openai", "cli"});
        providerCombo.setSelectedItem(s.apiProvider);

        apiKeyField = new JBPasswordField();
        apiKeyField.setText(s.apiKey);

        apiEndpointField = new JBTextField(s.apiEndpoint);
        modelIdField = new JBTextField(s.modelId);
        cliCommandField = new JBTextField(s.cliCommand);

        maxConcurrentSpinner = new JSpinner(new SpinnerNumberModel(s.maxConcurrentTasks, 1, 10, 1));
        maxRetriesSpinner = new JSpinner(new SpinnerNumberModel(s.maxRetriesPerTask, 0, 20, 1));
        backoffMsSpinner = new JSpinner(new SpinnerNumberModel((int) s.initialBackoffMs, 500, 60_000, 500));
        rpmLimitSpinner = new JSpinner(new SpinnerNumberModel(s.requestsPerMinuteLimit, 1, 1000, 1));
        approvalTimeoutSpinner = new JSpinner(new SpinnerNumberModel((int) s.approvalTimeoutMinutes, 1, 10080, 60));

        notifyOnApprovalCheckbox = new JBCheckBox("Notify when approval is needed", s.notifyOnApprovalNeeded);
        reportOutputDirField = new JBTextField(s.reportOutputDir);
        artifactOutputDirField = new JBTextField(s.artifactOutputDir);

        mainPanel = FormBuilder.createFormBuilder()
                .addSeparator()
                .addLabeledComponent("API Provider:", providerCombo)
                .addLabeledComponent("API Key:", apiKeyField)
                .addLabeledComponent("API Endpoint (optional):", apiEndpointField)
                .addLabeledComponent("Model ID:", modelIdField)
                .addLabeledComponent("CLI Command (for CLI provider):", cliCommandField)
                .addSeparator()
                .addLabeledComponent("Max Concurrent Tasks:", maxConcurrentSpinner)
                .addLabeledComponent("Max Retries per Task:", maxRetriesSpinner)
                .addLabeledComponent("Initial Backoff (ms):", backoffMsSpinner)
                .addLabeledComponent("Requests per Minute Limit:", rpmLimitSpinner)
                .addSeparator()
                .addLabeledComponent("Approval Timeout (minutes):", approvalTimeoutSpinner)
                .addComponent(notifyOnApprovalCheckbox)
                .addSeparator()
                .addLabeledComponent("Report Output Directory:", reportOutputDirField)
                .addLabeledComponent("Artifact Output Directory:", artifactOutputDirField)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        return mainPanel;
    }

    @Override
    public boolean isModified() {
        YariSettings.State s = YariSettings.getInstance().getState();
        return !providerCombo.getSelectedItem().equals(s.apiProvider)
                || !new String(apiKeyField.getPassword()).equals(s.apiKey)
                || !apiEndpointField.getText().equals(s.apiEndpoint)
                || !modelIdField.getText().equals(s.modelId)
                || !cliCommandField.getText().equals(s.cliCommand)
                || (int) maxConcurrentSpinner.getValue() != s.maxConcurrentTasks
                || (int) maxRetriesSpinner.getValue() != s.maxRetriesPerTask
                || (int) backoffMsSpinner.getValue() != (int) s.initialBackoffMs
                || (int) rpmLimitSpinner.getValue() != s.requestsPerMinuteLimit
                || (int) approvalTimeoutSpinner.getValue() != (int) s.approvalTimeoutMinutes
                || notifyOnApprovalCheckbox.isSelected() != s.notifyOnApprovalNeeded
                || !reportOutputDirField.getText().equals(s.reportOutputDir)
                || !artifactOutputDirField.getText().equals(s.artifactOutputDir);
    }

    @Override
    public void apply() throws ConfigurationException {
        YariSettings.State s = YariSettings.getInstance().getState();
        s.apiProvider = (String) providerCombo.getSelectedItem();
        s.apiKey = new String(apiKeyField.getPassword());
        s.apiEndpoint = apiEndpointField.getText();
        s.modelId = modelIdField.getText();
        s.cliCommand = cliCommandField.getText();
        s.maxConcurrentTasks = (int) maxConcurrentSpinner.getValue();
        s.maxRetriesPerTask = (int) maxRetriesSpinner.getValue();
        s.initialBackoffMs = (int) backoffMsSpinner.getValue();
        s.requestsPerMinuteLimit = (int) rpmLimitSpinner.getValue();
        s.approvalTimeoutMinutes = (int) approvalTimeoutSpinner.getValue();
        s.notifyOnApprovalNeeded = notifyOnApprovalCheckbox.isSelected();
        s.reportOutputDir = reportOutputDirField.getText();
        s.artifactOutputDir = artifactOutputDirField.getText();
    }

    @Override
    public void reset() {
        YariSettings.State s = YariSettings.getInstance().getState();
        providerCombo.setSelectedItem(s.apiProvider);
        apiKeyField.setText(s.apiKey);
        apiEndpointField.setText(s.apiEndpoint);
        modelIdField.setText(s.modelId);
        cliCommandField.setText(s.cliCommand);
        maxConcurrentSpinner.setValue(s.maxConcurrentTasks);
        maxRetriesSpinner.setValue(s.maxRetriesPerTask);
        backoffMsSpinner.setValue((int) s.initialBackoffMs);
        rpmLimitSpinner.setValue(s.requestsPerMinuteLimit);
        approvalTimeoutSpinner.setValue((int) s.approvalTimeoutMinutes);
        notifyOnApprovalCheckbox.setSelected(s.notifyOnApprovalNeeded);
        reportOutputDirField.setText(s.reportOutputDir);
        artifactOutputDirField.setText(s.artifactOutputDir);
    }
}
