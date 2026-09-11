package org.example.yari.ui;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.example.yari.checkpoint.CheckpointManager;
import org.example.yari.executor.BacklogExecutorService;
import org.example.yari.model.BacklogTask;
import org.example.yari.model.TaskState;
import org.example.yari.queue.TaskQueueManager;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.List;

/**
 * The main tool window panel displaying the task queue,
 * execution controls, and approval management.
 */
public class YariToolWindowPanel extends JPanel {

    private final Project project;
    private final JBTable taskTable;
    private final TaskTableModel tableModel;
    private final JBLabel statusLabel;
    private final JButton startStopBtn;
    private final JButton approveBtn;
    private final JButton rejectBtn;

    public YariToolWindowPanel(Project project) {
        super(new BorderLayout());
        this.project = project;

        // --- Header toolbar ---
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolbar.setBorder(JBUI.Borders.emptyBottom(4));

        startStopBtn = new JButton("Start");
        startStopBtn.addActionListener(e -> toggleExecutor());

        JButton pauseBtn = new JButton("Pause Queue");
        pauseBtn.addActionListener(e -> {
            project.getService(TaskQueueManager.class).pauseQueue();
            refresh();
        });

        JButton resumeBtn = new JButton("Resume Queue");
        resumeBtn.addActionListener(e -> {
            project.getService(TaskQueueManager.class).resumeQueue();
            refresh();
        });

        JButton reportBtn = new JButton("Generate Report");
        reportBtn.addActionListener(e -> generateReport());

        toolbar.add(startStopBtn);
        toolbar.add(pauseBtn);
        toolbar.add(resumeBtn);
        toolbar.add(new JToolBar.Separator());
        toolbar.add(reportBtn);

        // --- Task table ---
        tableModel = new TaskTableModel();
        taskTable = new JBTable(tableModel);
        taskTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        taskTable.getColumnModel().getColumn(2).setCellRenderer(new StateCellRenderer());
        taskTable.getSelectionModel().addListSelectionListener(e -> updateApprovalButtons());

        JBScrollPane scrollPane = new JBScrollPane(taskTable);

        // --- Approval panel ---
        JPanel approvalPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        approvalPanel.setBorder(JBUI.Borders.emptyTop(4));

        approveBtn = new JButton("✓ Approve");
        approveBtn.setEnabled(false);
        approveBtn.addActionListener(e -> approveSelected());

        rejectBtn = new JButton("✗ Reject");
        rejectBtn.setEnabled(false);
        rejectBtn.addActionListener(e -> rejectSelected());

        JButton viewOutputBtn = new JButton("View Output");
        viewOutputBtn.addActionListener(e -> viewSelectedOutput());

        approvalPanel.add(approveBtn);
        approvalPanel.add(rejectBtn);
        approvalPanel.add(new JToolBar.Separator());
        approvalPanel.add(viewOutputBtn);

        // --- Status bar ---
        statusLabel = new JBLabel("Ready");
        statusLabel.setBorder(JBUI.Borders.empty(2, 4));

        // --- Layout ---
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(approvalPanel, BorderLayout.NORTH);
        bottomPanel.add(statusLabel, BorderLayout.SOUTH);

        add(toolbar, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        // Listen for queue changes
        project.getService(TaskQueueManager.class).addListener(this::refresh);

        refresh();
    }

    private void toggleExecutor() {
        BacklogExecutorService executor = project.getService(BacklogExecutorService.class);
        if (executor.isRunning()) {
            executor.stop();
            startStopBtn.setText("Start");
        } else {
            executor.start();
            startStopBtn.setText("Stop");
        }
        refresh();
    }

    private void approveSelected() {
        BacklogTask task = getSelectedTask();
        if (task == null) return;
        CheckpointManager cm = project.getService(CheckpointManager.class);
        cm.approve(task.getId());
        refresh();
    }

    private void rejectSelected() {
        BacklogTask task = getSelectedTask();
        if (task == null) return;
        CheckpointManager cm = project.getService(CheckpointManager.class);
        cm.reject(task.getId());
        refresh();
    }

    private void viewSelectedOutput() {
        BacklogTask task = getSelectedTask();
        if (task == null) return;
        String output = task.getValidatedOutput() != null ? task.getValidatedOutput() : task.getRawOutput();
        if (output == null) {
            output = "(No output yet)";
        }
        JTextArea textArea = new JTextArea(output);
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setPreferredSize(new Dimension(600, 400));
        JOptionPane.showMessageDialog(this, scroll,
                "Output: " + task.getName(), JOptionPane.INFORMATION_MESSAGE);
    }

    private void generateReport() {
        BacklogExecutorService executor = project.getService(BacklogExecutorService.class);
        var report = executor.generateReport();
        JTextArea textArea = new JTextArea(report.toMarkdown());
        textArea.setEditable(false);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        JScrollPane scroll = new JScrollPane(textArea);
        scroll.setPreferredSize(new Dimension(700, 500));
        JOptionPane.showMessageDialog(this, scroll,
                "Execution Report", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateApprovalButtons() {
        BacklogTask task = getSelectedTask();
        boolean awaitingApproval = task != null && task.getState() == TaskState.AWAITING_APPROVAL;
        approveBtn.setEnabled(awaitingApproval);
        rejectBtn.setEnabled(awaitingApproval);
    }

    private BacklogTask getSelectedTask() {
        int row = taskTable.getSelectedRow();
        if (row < 0) return null;
        List<BacklogTask> tasks = project.getService(TaskQueueManager.class).getAllTasks();
        if (row >= tasks.size()) return null;
        return tasks.get(row);
    }

    private void refresh() {
        ApplicationManager.getApplication().invokeLater(() -> {
            tableModel.fireTableDataChanged();
            updateApprovalButtons();
            updateStatusLabel();
        });
    }

    private void updateStatusLabel() {
        TaskQueueManager qm = project.getService(TaskQueueManager.class);
        BacklogExecutorService exec = project.getService(BacklogExecutorService.class);
        CheckpointManager cm = project.getService(CheckpointManager.class);

        String status = String.format("Tasks: %d | Active: %d | Awaiting Approval: %d | Queue: %s | Executor: %s",
                qm.totalCount(), qm.activeCount(), cm.pendingCount(),
                qm.isQueuePaused() ? "PAUSED" : "ACTIVE",
                exec.isRunning() ? "RUNNING" : "STOPPED");
        statusLabel.setText(status);
    }

    /**
     * Table model backed by the TaskQueueManager.
     */
    private class TaskTableModel extends AbstractTableModel {
        private final String[] COLUMNS = {"ID", "Name", "State", "Priority", "Retries", "Error"};

        @Override
        public int getRowCount() {
            return project.getService(TaskQueueManager.class).getAllTasks().size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int col) {
            return COLUMNS[col];
        }

        @Override
        public Object getValueAt(int row, int col) {
            List<BacklogTask> tasks = project.getService(TaskQueueManager.class).getAllTasks();
            if (row >= tasks.size()) return "";
            BacklogTask task = tasks.get(row);
            return switch (col) {
                case 0 -> task.getId();
                case 1 -> task.getName();
                case 2 -> task.getState();
                case 3 -> task.getPriority();
                case 4 -> task.getRetryCount();
                case 5 -> task.getErrorMessage() != null ? task.getErrorMessage() : "";
                default -> "";
            };
        }
    }

    /**
     * Custom cell renderer that color-codes task states.
     */
    private static class StateCellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int col) {
            Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            if (value instanceof TaskState state) {
                switch (state) {
                    case COMPLETED -> setForeground(new JBColor(new Color(0, 128, 0), new Color(80, 200, 80)));
                    case FAILED -> setForeground(JBColor.RED);
                    case RUNNING, VALIDATING, VALIDATING_OUTPUT -> setForeground(JBColor.BLUE);
                    case AWAITING_APPROVAL -> setForeground(new JBColor(new Color(200, 140, 0), new Color(255, 180, 0)));
                    case PAUSED_RATE_LIMIT, PAUSED_USER -> setForeground(JBColor.GRAY);
                    default -> setForeground(table.getForeground());
                }
            }
            return c;
        }
    }
}
