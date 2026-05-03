import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ClaudeDesktopClient extends JFrame {
    private static final String APP_VERSION = "1.0.1";
    private static final String APP_TITLE = "Claude Opus Desktop Client " + APP_VERSION;
    private static final String DEFAULT_MODEL_NAME = "claude-opus-4-7";
    private static final String DEFAULT_MODEL_DISPLAY_NAME = "Claude Opus 4.7";
    private static final long DEFAULT_OUTPUT_TOKENS = 4_096L;
        private static final String DEFAULT_BASE_URL = ClaudeFileService.DEFAULT_PROXY_BASE_URL;
    private static final Path DEFAULT_PROJECT_JAVA_DIR = Paths.get("C:/Users/Alexey Tkachev/Documents/IdeaProjects/ClaudeClient2/src/main/java");
    private static final Path DEFAULT_CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".claude-opus-client");
    private static final Path BOOTSTRAP_CONFIG_FILE = DEFAULT_CONFIG_DIR.resolve("config.properties");

    private static final Map<String, Long> MODEL_MAX_OUTPUT_TOKENS = Map.of(DEFAULT_MODEL_NAME, 128_000L);
    private static final Map<String, String> MODEL_DISPLAY_NAMES = Map.of(DEFAULT_MODEL_NAME, DEFAULT_MODEL_DISPLAY_NAME);

    private final Map<String, ChatState> chats = new LinkedHashMap<>();
    private final FileTransport fileTransport = new TextFileTransport();

    private Path configDir = DEFAULT_CONFIG_DIR;
    private Path configFile = configDir.resolve("config.properties");
    private Path chatsFile = configDir.resolve("chats.ser");

    private JTextArea inputArea;
    private JTextPane outputArea;
    private JTextArea systemPromptArea;
    private JPasswordField apiKeyField;
    private JTextField baseUrlField;
    private JTextField maxOutputTokensField;
    private JTextField settingsDirField;
    private JTextField projectDirField;
    private JComboBox<String> sourceRootCombo;
    private JComboBox<String> modelCombo;

    private JLabel statusLabel;
    private JLabel keyLoadedLabel;
    private JLabel keyCheckLabel;
    private JLabel balanceLabel;
    private JLabel modelContextLabel;
    private JLabel modelMaxOutputLabel;
    private JLabel sessionInputLabel;
    private JLabel sessionOutputLabel;
    private JLabel lastErrorLabel;
    private JLabel lastRequestInputLabel;
    private JLabel lastRequestOutputLabel;
    private TitledBorder chatSettingsBorder;

    private JList<String> chatList;
    private DefaultListModel<String> chatListModel;

    private DefaultListModel<UserVisibleFile> localFilesModel;
    private DefaultListModel<UserVisibleFile> uploadedFilesModel;
    private DefaultListModel<UserVisibleFile> aiFilesModel;
    private JList<UserVisibleFile> localFilesList;
    private JList<UserVisibleFile> uploadedFilesList;
    private JList<UserVisibleFile> aiFilesList;

    private AnthropicClient client;
    private String currentChatName;
    private boolean loadingChat;

    public ClaudeDesktopClient() {
        super(APP_TITLE);
        setSize(1320, 900);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        initComponents();
        loadConfig();
        loadChats();
        if (chats.isEmpty()) createChat("Новый чат", true);
        refreshSettingsIndicators(false, false, currentChat().lastError);
        initializeClient(false);
    }

    private void initComponents() {
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setLeftComponent(createLeftPanel());
        mainSplit.setRightComponent(createWorkspace());
        mainSplit.setDividerLocation(285);
        add(mainSplit, BorderLayout.CENTER);
    }

    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Чаты"));

        chatListModel = new DefaultListModel<>();
        chatList = new JList<>(chatListModel);
        chatList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chatList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadChat(chatList.getSelectedValue());
        });
        chatList.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShowChatPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowChatPopup(e); }
        });

        JButton newChatBtn = new JButton("Новый чат");
        newChatBtn.addActionListener(e -> createChatFromDialog());

        JPanel buttons = new JPanel(new GridLayout(1, 1, 4, 4));
        buttons.add(newChatBtn);
        panel.add(new JScrollPane(chatList), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private void maybeShowChatPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int index = chatList.locationToIndex(e.getPoint());
        if (index >= 0) chatList.setSelectedIndex(index);
        if (chatList.getSelectedValue() == null) return;
        JPopupMenu menu = new JPopupMenu();
        JMenuItem rename = new JMenuItem("Rename");
        rename.addActionListener(ev -> renameSelectedChat());
        JMenuItem delete = new JMenuItem("Delete");
        delete.addActionListener(ev -> deleteSelectedChat());
        JMenuItem save = new JMenuItem("Save");
        save.addActionListener(ev -> saveSelectedChatAsMarkdown());
        menu.add(rename);
        menu.add(delete);
        menu.add(save);
        menu.show(chatList, e.getX(), e.getY());
    }

    private JTabbedPane createWorkspace() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Чат", createChatPanel());
        tabs.addTab("Настройки", createSettingsPanel());
        tabs.addTab("Файлы", createFilesPanel());
        return tabs;
    }

    private JPanel createChatPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        outputArea = new JTextPane();
        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        JScrollPane outputScroll = new JScrollPane(outputArea);
        outputScroll.setBorder(new TitledBorder("Ответы и история"));

        inputArea = new JTextArea(7, 80);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setBorder(new TitledBorder("Сообщение"));

        JButton sendButton = new JButton("Отправить запрос");
        sendButton.addActionListener(e -> sendMessage());
        JButton saveAnswerButton = new JButton("Сохранить историю в файл");
        saveAnswerButton.addActionListener(e -> saveOutputToFile());
        JButton clearInputButton = new JButton("Очистить ввод");
        clearInputButton.addActionListener(e -> inputArea.setText(""));

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttonPanel.add(clearInputButton);
        buttonPanel.add(saveAnswerButton);
        buttonPanel.add(sendButton);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(inputScroll, BorderLayout.CENTER);
        bottom.add(buttonPanel, BorderLayout.SOUTH);

        panel.add(outputScroll, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createSettingsPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));

        apiKeyField = new JPasswordField(24);
        baseUrlField = new JTextField(DEFAULT_BASE_URL, 24);
        balanceLabel = new JLabel("—");
        settingsDirField = new JTextField(DEFAULT_CONFIG_DIR.toString(), 42);

        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints t = gbcBase();
        t.gridy = 0;
        addLabeled(top, t, 0, "API key ProxyAPI:", apiKeyField);
        keyLoadedLabel = new JLabel("-");
        keyCheckLabel = new JLabel("-");
        JPanel keyStatus = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        keyStatus.add(new JLabel("Ключ загружен"));
        keyStatus.add(keyLoadedLabel);
        keyStatus.add(new JLabel("проверен"));
        keyStatus.add(keyCheckLabel);
        t.gridx = 2; t.weightx = 0; top.add(keyStatus, t);

        t.gridy = 1;
        addLabeled(top, t, 0, "Anthropic base URL:", baseUrlField);
        JPanel balancePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        balancePanel.add(new JLabel("Баланс:"));
        balancePanel.add(balanceLabel);
        t.gridx = 2; t.weightx = 0; top.add(balancePanel, t);
        panel.add(top, BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(8, 8));
        chatSettingsBorder = new TitledBorder("Чат");
        JPanel chatSettings = new JPanel(new GridBagLayout());
        chatSettings.setBorder(chatSettingsBorder);
        GridBagConstraints c = gbcBase();

        systemPromptArea = new JTextArea(7, 80);
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        JScrollPane systemScroll = new JScrollPane(systemPromptArea);
        systemScroll.setBorder(new TitledBorder("System prompt"));
        c.gridy = 0; c.gridx = 0; c.gridwidth = 3; c.fill = GridBagConstraints.BOTH; c.weightx = 1; c.weighty = 1;
        chatSettings.add(systemScroll, c);

        JPanel projectPanel = new JPanel(new GridBagLayout());
        projectPanel.setBorder(new TitledBorder("Проект"));
        GridBagConstraints p = gbcBase();
        projectDirField = new JTextField(defaultProjectDirString(), 42);
        JButton chooseProject = new JButton("...");
        chooseProject.setMargin(new Insets(2, 6, 2, 6));
        chooseProject.addActionListener(e -> chooseProjectDirectory());
        JButton openProject = new JButton("Открыть");
        openProject.setMargin(new Insets(2, 6, 2, 6));
        openProject.addActionListener(e -> openDirectory(Path.of(projectDirField.getText().trim())));
        JPanel projectRow = new JPanel(new BorderLayout(4, 0));
        projectRow.add(projectDirField, BorderLayout.CENTER);
        JPanel projectButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        projectButtons.add(chooseProject);
        projectButtons.add(openProject);
        projectRow.add(projectButtons, BorderLayout.EAST);
        p.gridy = 0;
        addLabeled(projectPanel, p, 0, "Папка проекта:", projectRow);

        sourceRootCombo = new JComboBox<>(new String[]{"", "main/java"});
        sourceRootCombo.setSelectedItem("main/java");
        sourceRootCombo.setPrototypeDisplayValue("main/java");
        JPanel sourceRootPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        sourceRootPanel.add(new JLabel("src/"));
        sourceRootPanel.add(sourceRootCombo);
        p.gridy = 1;
        addLabeled(projectPanel, p, 0, "Корень исходников:", sourceRootPanel);
        c.gridy = 1; c.gridx = 0; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL; c.weighty = 0;
        chatSettings.add(projectPanel, c);

        maxOutputTokensField = new JTextField(String.valueOf(DEFAULT_OUTPUT_TOKENS), 7);
        JButton set4096 = new JButton("set 4096");
        set4096.setMargin(new Insets(2, 6, 2, 6));
        set4096.addActionListener(e -> {
            maxOutputTokensField.setText(String.valueOf(DEFAULT_OUTPUT_TOKENS));
            persistCurrentChatSettings();
        });
        JButton setTechMax = new JButton("set max");
        setTechMax.setMargin(new Insets(2, 6, 2, 6));
        setTechMax.addActionListener(e -> {
            maxOutputTokensField.setText(String.valueOf(technicalMaxOutput(currentChat().modelName)));
            persistCurrentChatSettings();
        });
        JPanel maxPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        maxPanel.add(maxOutputTokensField);
        maxPanel.add(set4096);
        maxPanel.add(setTechMax);
        JLabel maxHint = new JLabel("(Резервирует деньги на счету Аггрегатора и может вызывать 402 при недостаточном балансе)");
        maxHint.setForeground(Color.GRAY);
        maxHint.setFont(maxHint.getFont().deriveFont(Font.ITALIC));
        maxPanel.add(maxHint);
        c.gridy = 2; c.gridwidth = 1; c.weighty = 0;
        addLabeled(chatSettings, c, 0, "max_tokens ответа:", maxPanel);

        modelCombo = new JComboBox<>(new String[]{DEFAULT_MODEL_NAME});
        modelCombo.setPrototypeDisplayValue(DEFAULT_MODEL_NAME);
        c.gridy = 3;
        addLabeled(chatSettings, c, 0, "Модель:", modelCombo);

        modelContextLabel = new JLabel("—");
        c.gridy = 4;
        addLabeled(chatSettings, c, 0, "Контекст модели:", modelContextLabel);

        modelMaxOutputLabel = new JLabel("—");
        c.gridy = 5;
        addLabeled(chatSettings, c, 0, "Технический максимум ответа:", modelMaxOutputLabel);

        JPanel stats = new JPanel(new GridLayout(1, 2, 24, 4));
        stats.setBorder(new TitledBorder("Статистика токенов"));
        sessionInputLabel = new JLabel("input: 0");
        sessionOutputLabel = new JLabel("output: 0");
        lastErrorLabel = new JLabel("Последняя ошибка: -");
        Font monoStatsFont = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        sessionInputLabel.setFont(monoStatsFont);
        sessionOutputLabel.setFont(monoStatsFont);
        JPanel sessionCol = new JPanel(new GridLayout(4, 1, 2, 2));
        sessionCol.add(new JLabel("Сессия:"));
        sessionCol.add(sessionInputLabel);
        sessionCol.add(sessionOutputLabel);
        sessionCol.add(lastErrorLabel);

        lastRequestInputLabel = new JLabel("input: 0");
        lastRequestOutputLabel = new JLabel("output: 0");
        lastRequestInputLabel.setFont(monoStatsFont);
        lastRequestOutputLabel.setFont(monoStatsFont);
        JPanel lastCol = new JPanel(new GridLayout(3, 1, 2, 2));
        lastCol.add(new JLabel("Последний запрос:"));
        lastCol.add(lastRequestInputLabel);
        lastCol.add(lastRequestOutputLabel);
        stats.add(sessionCol);
        stats.add(lastCol);
        c.gridy = 6;
        c.gridx = 0; c.gridwidth = 3; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        chatSettings.add(stats, c);

        center.add(chatSettings, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton saveBtn = new JButton("Сохранить настройки");
        saveBtn.addActionListener(e -> saveConfig());
        JButton initBtn = new JButton("Переинициализировать клиент");
        initBtn.addActionListener(e -> initializeClient(true));
        JButton checkKeyBtn = new JButton("Проверить ключ");
        checkKeyBtn.addActionListener(e -> checkApiKey());
        JButton balanceBtn = new JButton("Обновить баланс");
        balanceBtn.addActionListener(e -> updateBalance());
        buttons.add(saveBtn);
        buttons.add(initBtn);
        buttons.add(checkKeyBtn);
        buttons.add(balanceBtn);

        JPanel dirRow = new JPanel(new BorderLayout(4, 0));
        dirRow.add(settingsDirField, BorderLayout.CENTER);
        JPanel dirButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        JButton chooseDir = new JButton("...");
        chooseDir.setMargin(new Insets(2, 6, 2, 6));
        chooseDir.addActionListener(e -> chooseSettingsDirectory());
        JButton openDir = new JButton("Открыть");
        openDir.setMargin(new Insets(2, 6, 2, 6));
        openDir.addActionListener(e -> openDirectory(Path.of(settingsDirField.getText().trim())));
        dirButtons.add(chooseDir);
        dirButtons.add(openDir);
        dirRow.add(dirButtons, BorderLayout.EAST);

        JPanel globalButtons = new JPanel(new BorderLayout(4, 4));
        globalButtons.add(buttons, BorderLayout.NORTH);
        JPanel dirWrap = new JPanel(new GridBagLayout());
        GridBagConstraints d = gbcBase();
        d.gridy = 0;
        addLabeled(dirWrap, d, 0, "Директория настроек и истории чатов:", dirRow);
        globalButtons.add(dirWrap, BorderLayout.CENTER);
        statusLabel = new JLabel("Статус: -");
        globalButtons.add(statusLabel, BorderLayout.SOUTH);
        center.add(globalButtons, BorderLayout.SOUTH);

        panel.add(center, BorderLayout.CENTER);

        maxOutputTokensField.addActionListener(e -> persistCurrentChatSettings());
        modelCombo.addActionListener(e -> persistCurrentChatSettings());
        sourceRootCombo.addActionListener(e -> persistCurrentChatSettings());
        projectDirField.addActionListener(e -> persistCurrentChatSettings());
        return panel;
    }

    private JPanel createFilesPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new TitledBorder("Файлы"));

        localFilesModel = new DefaultListModel<>();
        uploadedFilesModel = new DefaultListModel<>();
        aiFilesModel = new DefaultListModel<>();

        localFilesList = new JList<>(localFilesModel);
        localFilesList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        uploadedFilesList = new JList<>(uploadedFilesModel);
        uploadedFilesList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        aiFilesList = new JList<>(aiFilesModel);
        aiFilesList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        localFilesList.setCellRenderer(new FileListRenderer());
        uploadedFilesList.setCellRenderer(new FileListRenderer());
        aiFilesList.setCellRenderer(new FileListRenderer());

        JButton addFilesButton = new JButton("Добавить файлы");
        addFilesButton.addActionListener(e -> chooseFiles());
        JButton removeFilesButton = new JButton("Удалить файл");
        removeFilesButton.addActionListener(e -> removeSelectedLocalFiles());
        JButton sendFilesButton = new JButton("Отправить файлы");
        sendFilesButton.addActionListener(e -> sendLocalFilesToModelArea());
        JButton downloadAiFilesButton = new JButton("Загрузить файлы");
        downloadAiFilesButton.addActionListener(e -> downloadAiFiles());
        JButton openDownloadsButton = new JButton("Открыть загрузки");
        openDownloadsButton.addActionListener(e -> openDirectory(downloadsDir()));

        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        c.gridx = 0;
        c.gridy = 0;
        JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topButtons.add(addFilesButton);
        topButtons.add(removeFilesButton);
        panel.add(topButtons, c);

        c.gridy = 1; c.fill = GridBagConstraints.BOTH; c.weighty = 0.34;
        panel.add(wrap(new JScrollPane(localFilesList), "Локальные файлы"), c);

        c.gridy = 2; c.fill = GridBagConstraints.HORIZONTAL; c.weighty = 0;
        JPanel sendRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sendRow.add(sendFilesButton);
        sendRow.add(new JLabel("Файлы будут приложены к следующему запросу в чат, но не будут показаны длинным текстом в истории."));
        panel.add(sendRow, c);

        c.gridy = 3; c.fill = GridBagConstraints.BOTH; c.weighty = 0.33;
        panel.add(wrap(new JScrollPane(uploadedFilesList), "Uploaded"), c);

        c.gridy = 4; c.fill = GridBagConstraints.BOTH; c.weighty = 0.33;
        panel.add(wrap(new JScrollPane(aiFilesList), "Файлы ИИ"), c);

        c.gridy = 5; c.fill = GridBagConstraints.HORIZONTAL; c.weighty = 0;
        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottomRow.add(downloadAiFilesButton);
        bottomRow.add(openDownloadsButton);
        bottomRow.add(new JLabel("Если файлов несколько или есть структура папок, они сохранятся ZIP-архивом в «Загрузки»."));
        panel.add(bottomRow, c);

        return panel;
    }

    private GridBagConstraints gbcBase() {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1.0;
        return c;
    }

    private void addLabeled(JPanel panel, GridBagConstraints c, int x, String label, Component component) {
        c.gridx = x; c.gridwidth = 1; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        JLabel l = new JLabel(label);
        l.setHorizontalAlignment(JLabel.LEFT);
        l.setPreferredSize(new java.awt.Dimension(185, l.getPreferredSize().height));
        panel.add(l, c);
        c.gridx = x + 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(component, c);
    }

    private JPanel wrap(Component component, String title) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new TitledBorder(title));
        p.add(component, BorderLayout.CENTER);
        return p;
    }

    private void sendMessage() {
        if (client == null) {
            showError("Клиент не инициализирован. Укажи API key и нажми переинициализацию.");
            return;
        }
        persistCurrentChatSettings();
        ChatState chat = currentChat();
        String userText = inputArea.getText().trim();
        List<UserVisibleFile> uploadedFiles = new ArrayList<>(chat.uploadedFiles);
        if (userText.isEmpty() && uploadedFiles.isEmpty()) return;

        String fileAppendix;
        try {
            fileAppendix = fileTransport.buildPromptAppendix(uploadedFiles);
        } catch (IOException ex) {
            showError("Не удалось подготовить файлы к запросу: " + ex.getMessage());
            return;
        }
        String fullUserMessage = userText + fileAppendix;
        String displayUserMessage = userText + buildUserFileSummary(uploadedFiles);

        appendOutput("Вы", displayUserMessage);
        inputArea.setText("");
        setControlsEnabled(false);
        statusLabel.setText("Статус: запрос выполняется: " + modelDisplayName(chat.modelName));

        List<MessageEntry> history = chat.messages;
        String modelName = chat.modelName;
        long maxTokens = chat.maxOutputTokens;
        String systemText = chat.systemPrompt + "\n\n" + fileReturnInstruction();

        new SwingWorker<Message, Void>() {
            @Override
            protected Message doInBackground() {
                MessageCreateParams.Builder builder = MessageCreateParams.builder()
                        .model(Model.CLAUDE_OPUS_4_7)
                        .maxTokens(maxTokens);
                if (!systemText.isBlank()) builder.system(systemText);
                for (MessageEntry entry : history) {
                    if ("user".equals(entry.role)) builder.addUserMessage(entry.apiContent);
                    else if ("assistant".equals(entry.role)) builder.addAssistantMessage(entry.apiContent);
                }
                builder.addUserMessage(fullUserMessage);
                return client.messages().create(builder.build());
            }

            @Override
            protected void done() {
                try {
                    Message response = get();
                    String rawAnswer = extractText(response);
                    FileProcessingResult processingResult = fileTransport.extractGeneratedFiles(rawAnswer, archiveSourceRoot(chat.sourceRoot));
                    for (UserVisibleFile file : processingResult.generatedFiles()) chat.aiFiles.add(file);
                    String visibleAnswer = processingResult.visibleAnswer().isBlank() ? rawAnswer : processingResult.visibleAnswer();
                    history.add(new MessageEntry("user", fullUserMessage, displayUserMessage));
                    history.add(new MessageEntry("assistant", rawAnswer, visibleAnswer));
                    appendOutput(modelDisplayName(modelName), visibleAnswer);
                    long in = response.usage().inputTokens();
                    long out = response.usage().outputTokens();
                    chat.lastInputTokens = in;
                    chat.lastOutputTokens = out;
                    chat.sessionInputTokens += in;
                    chat.sessionOutputTokens += out;
                    chat.lastError = "-";
                    chat.uploadedFiles.clear();
                    refreshFileModelsFromChat(chat);
                    refreshChatSettingsPanel();
                    saveChats();
                    statusLabel.setText("Статус: готово. Модель: " + modelName + ".");
                    refreshSettingsIndicators(true, true, "-");
                } catch (Exception ex) {
                    String message = rootMessage(ex);
                    chat.lastError = message;
                    appendOutput("Ошибка", message);
                    statusLabel.setText("Статус: ошибка запроса");
                    refreshChatSettingsPanel();
                    refreshSettingsIndicators(true, false, message);
                    saveChats();
                } finally {
                    setControlsEnabled(true);
                }
            }
        }.execute();
    }

    private String fileReturnInstruction() {
        return "Если нужно вернуть пользователю файлы, не печатай их как обычный длинный текст. "
                + "Верни каждый файл строго отдельным блоком вида:\n"
                + "```file:relative/path/FileName.ext\n<полное содержимое файла>\n```\n"
                + "Для Java-файлов обязательно указывай корректный package; клиент сам разложит классы в src/main/java по package. "
                + "Не используй file_id в ответе пользователю.";
    }

    private String buildUserFileSummary(List<UserVisibleFile> files) {
        if (files == null || files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\nФайлы добавлены в запрос:\n");
        for (int i = 0; i < files.size(); i++) {
            UserVisibleFile file = files.get(i);
            sb.append(i + 1).append(". ")
                    .append(file.displayPathFromSrc())
                    .append(" — ")
                    .append(file.sizeLabel())
                    .append("\n");
        }
        return sb.toString();
    }

    private String extractText(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) block.text().ifPresent(textBlock -> sb.append(textBlock.text()));
        return sb.toString().isBlank() ? response.toString() : sb.toString();
    }

    private void chooseFiles() {
        JFileChooser chooser = new JFileChooser(Files.isDirectory(DEFAULT_PROJECT_JAVA_DIR) ? DEFAULT_PROJECT_JAVA_DIR.toFile() : downloadsDir().toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Файлы кода, текста и архивы", "java", "md", "txt", "zip", "xml", "properties", "json", "yml", "yaml", "sql", "gradle", "html", "css", "js", "ts"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            ChatState chat = currentChat();
            for (java.io.File file : chooser.getSelectedFiles()) {
                Path path = file.toPath();
                String relative = inferRelativePath(path);
                UserVisibleFile visible = new UserVisibleFile(path.getFileName().toString(), relative, path, null, UserVisibleFile.SourceKind.LOCAL);
                chat.localFiles.add(visible);
                localFilesModel.addElement(visible);
            }
            saveChats();
        }
    }

    private void removeSelectedLocalFiles() {
        ChatState chat = currentChat();
        List<UserVisibleFile> selected = localFilesList.getSelectedValuesList();
        for (UserVisibleFile file : selected) {
            chat.localFiles.remove(file);
            localFilesModel.removeElement(file);
        }
        saveChats();
    }

    private void sendLocalFilesToModelArea() {
        ChatState chat = currentChat();
        List<UserVisibleFile> selected = localFilesList.getSelectedValuesList();
        if (selected.isEmpty()) selected = new ArrayList<>(chat.localFiles);
        if (selected.isEmpty()) return;
        try {
            List<Path> paths = selected.stream().map(UserVisibleFile::localPath).toList();
            List<UserVisibleFile> uploaded = fileTransport.prepareForModel(paths);
            for (UserVisibleFile file : uploaded) chat.uploadedFiles.add(file);
            chat.localFiles.removeAll(selected);
            refreshFileModelsFromChat(chat);
            saveChats();
            statusLabel.setText("Статус: файлы подготовлены к отправке модели: " + uploaded.size());
        } catch (IOException ex) {
            showError("Не удалось подготовить файлы: " + ex.getMessage());
        }
    }

    private void downloadAiFiles() {
        ChatState chat = currentChat();
        List<UserVisibleFile> selected = aiFilesList.getSelectedValuesList();
        if (selected.isEmpty()) selected = new ArrayList<>(chat.aiFiles);
        if (selected.isEmpty()) {
            showError("Нет файлов ИИ для загрузки.");
            return;
        }
        try {
            Path savedTo = fileTransport.saveGeneratedFiles(selected, downloadsDir(), modelDisplayName(chat.modelName), currentChatName, archiveSourceRoot(chat.sourceRoot));
            statusLabel.setText("Статус: файлы ИИ сохранены: " + savedTo);
            JOptionPane.showMessageDialog(this, "Сохранено: " + savedTo);
        } catch (IOException ex) {
            showError("Не удалось сохранить файлы ИИ: " + ex.getMessage());
        }
    }

    private String inferRelativePath(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        int idx = normalized.indexOf("/src/");
        if (idx >= 0) return normalized.substring(idx + 1);
        return path.getFileName().toString();
    }

    private void refreshFileModelsFromChat(ChatState chat) {
        localFilesModel.clear();
        uploadedFilesModel.clear();
        aiFilesModel.clear();
        for (UserVisibleFile f : chat.localFiles) localFilesModel.addElement(f);
        for (UserVisibleFile f : chat.uploadedFiles) uploadedFilesModel.addElement(f);
        for (UserVisibleFile f : chat.aiFiles) aiFilesModel.addElement(f);
    }

    private Path downloadsDir() {
        Path downloads = Paths.get(System.getProperty("user.home"), "Downloads");
        return Files.isDirectory(downloads) ? downloads : Paths.get(System.getProperty("user.home"));
    }
    private String defaultProjectDirString() {
        Path p = DEFAULT_PROJECT_JAVA_DIR;
        for (int i = 0; i < 3 && p != null; i++) p = p.getParent();
        return p == null ? "" : p.toString();
    }

    private void chooseProjectDirectory() {
        Path start = projectDirField == null || projectDirField.getText().isBlank()
                ? Path.of(defaultProjectDirString())
                : Path.of(projectDirField.getText().trim());
        JFileChooser chooser = new JFileChooser(Files.isDirectory(start) ? start.toFile() : downloadsDir().toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            projectDirField.setText(chooser.getSelectedFile().toPath().toString());
            persistCurrentChatSettings();
        }
    }

    private void openDirectory(Path dir) {
        try {
            Files.createDirectories(dir);
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            showError("Не удалось открыть папку: " + ex.getMessage());
        }
    }


    private void initializeClient(boolean showDialog) {
        String key = readApiKey();
        if (key.isBlank()) {
            refreshSettingsIndicators(false, false, currentChat().lastError);
            if (showDialog) showError("API key пустой.");
            return;
        }
        String baseUrl = baseUrlField.getText().isBlank() ? DEFAULT_BASE_URL : baseUrlField.getText().trim();
        client = AnthropicOkHttpClient.builder().apiKey(key).baseUrl(baseUrl).build();
        statusLabel.setText("Статус: клиент инициализирован");
        refreshSettingsIndicators(true, false, currentChat().lastError);
    }

    private void checkApiKey() {
        initializeClient(false);
        if (client == null) return;
        new SwingWorker<Message, Void>() {
            @Override protected Message doInBackground() {
                return client.messages().create(MessageCreateParams.builder()
                        .model(Model.CLAUDE_OPUS_4_7)
                        .maxTokens(16)
                        .addUserMessage("Ответь одним словом: OK")
                        .build());
            }
            @Override protected void done() {
                try {
                    Message msg = get();
                    refreshSettingsIndicators(true, true, "-");
                } catch (Exception ex) {
                    String message = rootMessage(ex);
                    refreshSettingsIndicators(true, false, message);
                    currentChat().lastError = message;
                    refreshChatSettingsPanel();
                    showError(message);
                }
            }
        }.execute();
    }

    private void updateBalance() {
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                return new ClaudeFileService(readApiKey(), baseUrlField.getText()).readProxyBalance();
            }
            @Override protected void done() {
                try {
                    balanceLabel.setText(formatBalance(get()));
                } catch (Exception ex) {
                    balanceLabel.setText("—");
                    String message = rootMessage(ex);
                    currentChat().lastError = message;
                    refreshChatSettingsPanel();
                    showError(message);
                }
            }
        }.execute();
    }

    private String formatBalance(String raw) {
        if (raw == null || raw.isBlank()) return "—";
        Matcher m = Pattern.compile("-?\\d+(?:[.,]\\d+)?").matcher(raw);
        if (!m.find()) return raw;
        double value = Double.parseDouble(m.group().replace(',', '.'));
        DecimalFormat df = new DecimalFormat("0.00", DecimalFormatSymbols.getInstance(Locale.US));
        String currency = raw.toLowerCase(Locale.ROOT).contains("rub") || raw.contains("₽") || raw.toLowerCase(Locale.ROOT).contains("rur") ? " ₽" : "";
        return df.format(value) + currency;
    }

    private void refreshSettingsIndicators(boolean keyLoaded, boolean checked, String lastError) {
        if (keyLoadedLabel != null) keyLoadedLabel.setText(keyLoaded || !readApiKey().isBlank() ? "✅" : "-");
        if (keyCheckLabel != null) keyCheckLabel.setText(checked ? "✅" : "-");
        if (lastErrorLabel != null) lastErrorLabel.setText("Последняя ошибка: " + (lastError == null || lastError.isBlank() ? "-" : lastError));
    }

    private long readMaxOutputTokens() {
        try {
            long value = Long.parseLong(maxOutputTokensField.getText().trim());
            return Math.max(1L, Math.min(value, technicalMaxOutput(currentChat().modelName)));
        } catch (NumberFormatException ex) {
            return DEFAULT_OUTPUT_TOKENS;
        }
    }

    private String readApiKey() {
        return new String(apiKeyField.getPassword()).trim();
    }

    private void loadConfig() {
        Properties bootstrap = new Properties();
        if (Files.exists(BOOTSTRAP_CONFIG_FILE)) {
            try (var in = Files.newInputStream(BOOTSTRAP_CONFIG_FILE)) {
                bootstrap.load(in);
                String configuredDir = bootstrap.getProperty("config_dir", "").trim();
                if (!configuredDir.isBlank()) setConfigDir(Paths.get(configuredDir));
            } catch (IOException ignored) { }
        }
        if (!Files.exists(configFile)) return;
        Properties properties = new Properties();
        try (var in = Files.newInputStream(configFile)) {
            properties.load(in);
            apiKeyField.setText(properties.getProperty("api_key", ""));
            baseUrlField.setText(properties.getProperty("base_url", DEFAULT_BASE_URL));
            settingsDirField.setText(configDir.toString());
        } catch (IOException ex) {
            statusLabel.setText("Статус: не удалось прочитать config.properties: " + ex.getMessage());
        }
    }

    private void saveConfig() {
        persistCurrentChatSettings();
        try {
            Path chosenDir = Paths.get(settingsDirField.getText().trim());
            setConfigDir(chosenDir);
            Files.createDirectories(configDir);
            Properties properties = new Properties();
            properties.setProperty("api_key", readApiKey());
            properties.setProperty("base_url", baseUrlField.getText().trim());
            properties.setProperty("config_dir", configDir.toString());
            try (var out = Files.newOutputStream(configFile)) {
                properties.store(out, "Claude Opus desktop client runtime config");
            }
            Files.createDirectories(DEFAULT_CONFIG_DIR);
            try (var out = Files.newOutputStream(BOOTSTRAP_CONFIG_FILE)) {
                properties.store(out, "Claude Opus desktop client bootstrap config");
            }
            saveChats();
            initializeClient(false);
            JOptionPane.showMessageDialog(this, "Настройки сохранены в " + configFile);
        } catch (IOException ex) {
            refreshSettingsIndicators(!readApiKey().isBlank(), false, ex.getMessage());
            showError("Не удалось сохранить настройки: " + ex.getMessage());
        }
    }

    private void setConfigDir(Path dir) {
        configDir = dir.toAbsolutePath().normalize();
        configFile = configDir.resolve("config.properties");
        chatsFile = configDir.resolve("chats.ser");
        if (settingsDirField != null) settingsDirField.setText(configDir.toString());
    }

    private void chooseSettingsDirectory() {
        JFileChooser chooser = new JFileChooser(configDir.toFile());
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            settingsDirField.setText(chooser.getSelectedFile().toPath().toString());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadChats() {
        if (!Files.exists(chatsFile)) return;
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(chatsFile))) {
            Object object = input.readObject();
            chats.clear();
            if (object instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String name = String.valueOf(entry.getKey());
                    Object value = entry.getValue();
                    if (value instanceof ChatState state) chats.put(name, state);
                    else if (value instanceof List<?> list) {
                        ChatState state = new ChatState();
                        state.messages.addAll((List<MessageEntry>) list);
                        chats.put(name, state);
                    }
                }
                refreshChatList();
            }
        } catch (Exception ex) {
            statusLabel.setText("Статус: историю чатов не удалось загрузить: " + ex.getMessage());
        }
    }

    private void saveChats() {
        try {
            Files.createDirectories(configDir);
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(chatsFile))) {
                output.writeObject(chats);
            }
            saveChatSnapshots();
        } catch (IOException ex) {
            statusLabel.setText("Статус: историю чатов не удалось сохранить: " + ex.getMessage());
        }
    }

    private void saveChatSnapshots() throws IOException {
        Path dir = configDir.resolve("chats-md");
        Files.createDirectories(dir);
        try (var stream = Files.list(dir)) {
            stream.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md"))
                    .forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (IOException ignored) { }
                    });
        }
        StringBuilder index = new StringBuilder("Автосохранённые истории чатов\n\n");
        for (Map.Entry<String, ChatState> entry : chats.entrySet()) {
            String fileName = safeFileName(modelDisplayName(entry.getValue().modelName)) + "_" + safeFileName(entry.getKey()) + ".md";
            Path file = dir.resolve(fileName);
            Files.writeString(file, chatAsMarkdown(entry.getKey(), entry.getValue()), StandardCharsets.UTF_8);
            index.append(entry.getKey()).append(" -> ").append(fileName).append("\n");
        }
        Files.writeString(configDir.resolve("history-index.txt"), index.toString(), StandardCharsets.UTF_8);
    }

    private void createChatFromDialog() {
        String name = JOptionPane.showInputDialog(this, "Название чата:", "Чат " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        if (name != null && !name.isBlank()) createChat(name.trim(), true);
    }

    private void createChat(String name, boolean select) {
        persistCurrentChatSettings();
        String unique = uniqueChatName(name);
        ChatState state = new ChatState();
        state.modelName = String.valueOf(modelCombo == null ? DEFAULT_MODEL_NAME : modelCombo.getSelectedItem());
        if (projectDirField != null) state.projectDir = projectDirField.getText().trim();
        if (sourceRootCombo != null && sourceRootCombo.getSelectedItem() != null) state.sourceRoot = String.valueOf(sourceRootCombo.getSelectedItem());
        chats.put(unique, state);
        chatListModel.addElement(unique);
        if (select || currentChatName == null) chatList.setSelectedValue(unique, true);
        saveChats();
    }

    private String uniqueChatName(String name) {
        String base = name == null || name.isBlank() ? "Новый чат" : name.trim();
        String unique = base;
        int i = 2;
        while (chats.containsKey(unique)) unique = base + " " + i++;
        return unique;
    }

    private void refreshChatList() {
        chatListModel.clear();
        for (String name : chats.keySet()) chatListModel.addElement(name);
        if (!chats.isEmpty()) chatList.setSelectedIndex(0);
    }

    private void renameSelectedChat() {
        String selected = chatList.getSelectedValue();
        if (selected == null) return;
        String newName = JOptionPane.showInputDialog(this, "Новое название чата:", selected);
        if (newName == null || newName.isBlank() || newName.equals(selected)) return;
        newName = uniqueChatName(newName.trim());
        LinkedHashMap<String, ChatState> reordered = new LinkedHashMap<>();
        for (Map.Entry<String, ChatState> entry : chats.entrySet()) {
            if (entry.getKey().equals(selected)) reordered.put(newName, entry.getValue());
            else reordered.put(entry.getKey(), entry.getValue());
        }
        chats.clear();
        chats.putAll(reordered);
        currentChatName = newName;
        refreshChatList();
        chatList.setSelectedValue(newName, true);
        saveChats();
    }

    private void deleteSelectedChat() {
        String selected = chatList.getSelectedValue();
        if (selected == null) return;
        int answer = JOptionPane.showConfirmDialog(this, "Удалить чат «" + selected + "»?", "Delete", JOptionPane.YES_NO_OPTION);
        if (answer != JOptionPane.YES_OPTION) return;
        chats.remove(selected);
        chatListModel.removeElement(selected);
        if (chats.isEmpty()) createChat("Новый чат", true);
        else chatList.setSelectedIndex(0);
        saveChats();
    }

    private void saveSelectedChatAsMarkdown() {
        String selected = chatList.getSelectedValue();
        if (selected == null) return;
        ChatState chat = chats.get(selected);
        JFileChooser chooser = new JFileChooser(downloadsDir().toFile());
        chooser.setSelectedFile(new java.io.File(safeFileName(modelDisplayName(chat.modelName)) + "_" + safeFileName(selected) + ".md"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.writeString(chooser.getSelectedFile().toPath(), chatAsMarkdown(selected, chat), StandardCharsets.UTF_8);
                statusLabel.setText("Статус: чат сохранён: " + chooser.getSelectedFile());
            } catch (IOException ex) {
                showError("Не удалось сохранить чат: " + ex.getMessage());
            }
        }
    }

    private String chatAsMarkdown(String chatName, ChatState chat) {
        StringBuilder sb = new StringBuilder("# ").append(chatName).append("\n\n");
        sb.append("Модель: ").append(chat.modelName).append("\n\n");
        for (MessageEntry entry : chat.messages) {
            sb.append("## ").append("assistant".equals(entry.role) ? modelDisplayName(chat.modelName) : "Вы").append("\n\n");
            sb.append(entry.displayContent == null ? entry.apiContent : entry.displayContent).append("\n\n");
        }
        return sb.toString();
    }

    private void loadChat(String chatName) {
        if (loadingChat || chatName == null || !chats.containsKey(chatName)) return;
        persistCurrentChatSettings();
        loadingChat = true;
        currentChatName = chatName;
        ChatState chat = chats.get(chatName);
        outputArea.setText("");
        for (MessageEntry entry : chat.messages) {
            appendOutput("assistant".equals(entry.role) ? modelDisplayName(chat.modelName) : "Вы", entry.displayContent == null ? entry.apiContent : entry.displayContent);
        }
        refreshFileModelsFromChat(chat);
        applyChatSettingsToUi(chatName, chat);
        loadingChat = false;
    }

    private ChatState currentChat() {
        if (currentChatName == null || !chats.containsKey(currentChatName)) {
            if (chats.isEmpty()) {
                ChatState state = new ChatState();
                chats.put("Новый чат", state);
                currentChatName = "Новый чат";
            } else {
                currentChatName = chats.keySet().iterator().next();
            }
        }
        return chats.get(currentChatName);
    }

    private void applyChatSettingsToUi(String name, ChatState chat) {
        if (chatSettingsBorder != null) {
            chatSettingsBorder.setTitle(name);
            repaint();
        }
        systemPromptArea.setText(chat.systemPrompt);
        projectDirField.setText(chat.projectDir == null || chat.projectDir.isBlank() ? defaultProjectDirString() : chat.projectDir);
        sourceRootCombo.setSelectedItem(sourceRootComboValue(chat.sourceRoot));
        maxOutputTokensField.setText(String.valueOf(chat.maxOutputTokens));
        modelCombo.setSelectedItem(chat.modelName);
        modelCombo.setEnabled(chat.messages.isEmpty());
        modelContextLabel.setText(chat.modelContextTokens > 0 ? String.valueOf(chat.modelContextTokens) : "—");
        modelMaxOutputLabel.setText(String.valueOf(technicalMaxOutput(chat.modelName)));
        refreshChatSettingsPanel();
    }

    private void persistCurrentChatSettings() {
        if (loadingChat || currentChatName == null || !chats.containsKey(currentChatName) || maxOutputTokensField == null) return;
        ChatState chat = chats.get(currentChatName);
        chat.systemPrompt = systemPromptArea.getText();
        chat.projectDir = projectDirField.getText().trim();
        chat.sourceRoot = sourceRootCombo.getSelectedItem() == null ? "" : String.valueOf(sourceRootCombo.getSelectedItem());
        chat.maxOutputTokens = readMaxOutputTokens();
        if (chat.messages.isEmpty() && modelCombo.getSelectedItem() != null) chat.modelName = String.valueOf(modelCombo.getSelectedItem());
        chat.modelTechnicalMaxOutput = technicalMaxOutput(chat.modelName);
        refreshChatSettingsPanel();
    }

    private void refreshChatSettingsPanel() {
        if (currentChatName == null || !chats.containsKey(currentChatName) || sessionInputLabel == null) return;
        ChatState chat = chats.get(currentChatName);
        sessionInputLabel.setText(formatTokenLine("input", chat.sessionInputTokens));
        sessionOutputLabel.setText(formatTokenLine("output", chat.sessionOutputTokens));
        lastRequestInputLabel.setText(formatTokenLine("input", chat.lastInputTokens));
        lastRequestOutputLabel.setText(formatTokenLine("output", chat.lastOutputTokens));
        lastErrorLabel.setText("Последняя ошибка: " + (chat.lastError == null || chat.lastError.isBlank() ? "-" : chat.lastError));
        modelContextLabel.setText(chat.modelContextTokens > 0 ? String.valueOf(chat.modelContextTokens) : "—");
        modelMaxOutputLabel.setText(String.valueOf(technicalMaxOutput(chat.modelName)));
        if (chatSettingsBorder != null) chatSettingsBorder.setTitle(currentChatName);
    }

    private String formatTokenLine(String label, long value) {
        return String.format("%-7s %12d", label + ":", value);
    }


    private String sourceRootComboValue(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = UserVisibleFile.normalize(value);
        if (normalized.equals("src/main/java")) return "main/java";
        if (normalized.equals("src")) return "";
        if (normalized.startsWith("src/")) return normalized.substring("src/".length());
        return normalized;
    }

    private String archiveSourceRoot(String comboValue) {
        String value = comboValue == null ? "" : comboValue.trim();
        if (value.isBlank()) return "src";
        String normalized = UserVisibleFile.normalize(value);
        if (normalized.startsWith("src/")) return normalized;
        return "src/" + normalized;
    }

    private long technicalMaxOutput(String modelName) {
        return MODEL_MAX_OUTPUT_TOKENS.getOrDefault(modelName, 0L);
    }

    private String modelDisplayName(String modelName) {
        return MODEL_DISPLAY_NAMES.getOrDefault(modelName, modelName);
    }

    private void appendOutput(String who, String text) {
        StyledDocument doc = outputArea.getStyledDocument();
        Style normal = outputArea.addStyle("normal", null);
        StyleConstants.setFontFamily(normal, Font.MONOSPACED);
        StyleConstants.setFontSize(normal, 13);
        StyleConstants.setItalic(normal, false);
        StyleConstants.setForeground(normal, Color.BLACK);

        Style header = outputArea.addStyle("header", normal);
        StyleConstants.setBold(header, true);

        Style fileMeta = outputArea.addStyle("fileMeta", normal);
        StyleConstants.setItalic(fileMeta, true);
        StyleConstants.setForeground(fileMeta, Color.GRAY);

        try {
            doc.insertString(doc.getLength(), "\n=== " + who + " ===\n", header);
            String safeText = text == null ? "" : text;
            for (String line : safeText.split("\\R", -1)) {
                Style style = isFileSummaryLine(line) ? fileMeta : normal;
                doc.insertString(doc.getLength(), line + "\n", style);
            }
        } catch (BadLocationException ex) {
            throw new IllegalStateException(ex);
        }
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }

    private boolean isFileSummaryLine(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        return trimmed.matches("\\d+\\.\\s+.+\\s+—\\s+.+КБ")
                || trimmed.equals("Файлы добавлены в запрос:")
                || trimmed.equals("Получены файлы ИИ:")
                || trimmed.equals("Открой вкладку «Файлы» и нажми «Загрузить файлы».");
    }

    private void saveOutputToFile() {
        JFileChooser chooser = new JFileChooser(downloadsDir().toFile());
        chooser.setSelectedFile(new java.io.File(safeFileName(modelDisplayName(currentChat().modelName)) + "_" + safeFileName(currentChatName) + "-history.md"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.writeString(chooser.getSelectedFile().toPath(), outputArea.getText(), StandardCharsets.UTF_8);
                statusLabel.setText("Статус: сохранено: " + chooser.getSelectedFile());
            } catch (IOException ex) {
                showError("Не удалось сохранить файл: " + ex.getMessage());
            }
        }
    }

    private void setControlsEnabled(boolean enabled) { inputArea.setEnabled(enabled); }

    private void showError(String message) { JOptionPane.showMessageDialog(this, message, "Ошибка", JOptionPane.ERROR_MESSAGE); }

    private String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage() == null ? current.toString() : current.getMessage();
        if (message.contains("Files API") && message.contains("HTTP 404")) {
            return message + "\n\nВероятная причина: текущий ProxyAPI endpoint не поддерживает Anthropic Files API /v1/files. Пользовательский интерфейс файлов уже отделён от транспорта.";
        }
        if (message.contains("Insufficient balance")) {
            return message + "\n\nВероятная причина: слишком большой max_tokens. Это лимит максимального ответа, а не контекста. Поставь 4096 или 8192 и повтори.";
        }
        return message;
    }

    private String safeFileName(String value) {
        String safe = value == null || value.isBlank() ? "chat" : value.trim();
        safe = safe.replaceAll("[^\\p{L}\\p{N}._-]+", "_").replaceAll("_+", "_");
        return safe.isBlank() ? "chat" : safe;
    }

    private static class FileListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof UserVisibleFile file) {
                String path = file.displayPathFromSrc();
                int slash = path.lastIndexOf('/');
                String prefix = slash >= 0 ? path.substring(0, slash + 1) : "";
                String name = slash >= 0 ? path.substring(slash + 1) : path;
                String gray = isSelected ? "#dddddd" : "#777777";
                String black = isSelected ? "#ffffff" : "#000000";
                setText("<html><span style='color:" + gray + "; font-style:italic;'>" + (index + 1) + ". " + escapeHtml(prefix)
                        + "</span><span style='color:" + black + ";'>" + escapeHtml(name)
                        + "</span><span style='color:" + gray + "; font-style:italic;'> — " + escapeHtml(file.sizeLabel()) + "</span></html>");
            }
            return this;
        }
        private static String escapeHtml(String text) {
            return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    public static class MessageEntry implements Serializable {
        @Serial private static final long serialVersionUID = 3L;
        public String role;
        public String apiContent;
        public String displayContent;
        public MessageEntry(String role, String apiContent, String displayContent) {
            this.role = role;
            this.apiContent = apiContent;
            this.displayContent = displayContent;
        }
    }

    public static class ChatState implements Serializable {
        @Serial private static final long serialVersionUID = 1L;
        public List<MessageEntry> messages = new ArrayList<>();
        public List<UserVisibleFile> localFiles = new ArrayList<>();
        public List<UserVisibleFile> uploadedFiles = new ArrayList<>();
        public List<UserVisibleFile> aiFiles = new ArrayList<>();
        public String modelName = DEFAULT_MODEL_NAME;
        public String systemPrompt = "Ты — ведущий Java-разработчик. Работаешь только с Claude Opus 4.7. Отвечай точно, проверяй код, предлагай минимальные рабочие правки.";
        public String projectDir = "";
        public String sourceRoot = "main/java";
        public long maxOutputTokens = DEFAULT_OUTPUT_TOKENS;
        public long modelContextTokens = 0L;
        public long modelTechnicalMaxOutput = MODEL_MAX_OUTPUT_TOKENS.getOrDefault(DEFAULT_MODEL_NAME, 0L);
        public long sessionInputTokens;
        public long sessionOutputTokens;
        public long lastInputTokens;
        public long lastOutputTokens;
        public String lastError = "-";
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClaudeDesktopClient().setVisible(true));
    }
}
