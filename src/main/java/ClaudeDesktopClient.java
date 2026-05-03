import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class ClaudeDesktopClient extends JFrame
{
    private static final String APP_TITLE = "Claude Opus 4.7 Desktop Client";
    private static final String MODEL_NAME = "claude-opus-4-7";
    private static final long MAX_CONTEXT_TOKENS = 1_000_000L;
    private static final long MAX_OUTPUT_TOKENS = 128_000L;
    private static final long DEFAULT_OUTPUT_TOKENS = 4_096L;
    private static final long SAFE_PROXY_OUTPUT_TOKENS = 32_000L;
    private static final String DEFAULT_BASE_URL = ClaudeFileService.DEFAULT_PROXY_BASE_URL;
    private static final Path DEFAULT_PROJECT_JAVA_DIR = Paths.get("C:/Users/Alexey Tkachev/Documents/IdeaProjects/ClaudeClient2/src/main/java");
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".claude-opus-client");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");
    private static final Path CHATS_FILE = CONFIG_DIR.resolve("chats.ser");

    private final Map<String, List<MessageEntry>> conversations = new LinkedHashMap<>();
    private final FileTransport fileTransport = new TextFileTransport();

    private JTextArea inputArea;
    private JTextArea outputArea;
    private JTextArea systemPromptArea;
    private JPasswordField apiKeyField;
    private JTextField baseUrlField;
    private JTextField maxOutputTokensField;

    private JLabel tokenInfoLabel;
    private JLabel statusLabel;
    private JLabel keyLoadedLabel;
    private JLabel keyCheckLabel;
    private JLabel lastErrorLabel;
    private JLabel balanceLabel;

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
    private long totalInputTokens;
    private long totalOutputTokens;

    public ClaudeDesktopClient()
    {
        super(APP_TITLE);
        setSize(1320, 900);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        initComponents();
        loadConfig();
        loadChats();
        if (conversations.isEmpty())
        {
            createChat("Новый чат", false);
        }
        refreshSettingsIndicators("не проверялся", "-");
        if (!readApiKey().isBlank())
        {
            initializeClient(false);
        }
    }

    private void initComponents()
    {
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplit.setLeftComponent(createLeftPanel());
        mainSplit.setRightComponent(createWorkspace());
        mainSplit.setDividerLocation(285);
        add(mainSplit, BorderLayout.CENTER);
    }

    private JPanel createLeftPanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(new TitledBorder("Чаты"));

        chatListModel = new DefaultListModel<>();
        chatList = new JList<>(chatListModel);
        chatList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        chatList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting())
            {
                loadChat(chatList.getSelectedValue());
            }
        });

        JButton newChatBtn = new JButton("Новый чат");
        newChatBtn.addActionListener(e -> createChatFromDialog());
        JButton deleteChatBtn = new JButton("Удалить");
        deleteChatBtn.addActionListener(e -> deleteSelectedChat());
        JButton saveChatsBtn = new JButton("Сохранить чаты");
        saveChatsBtn.addActionListener(e -> saveChats());

        JPanel buttons = new JPanel(new GridLayout(3, 1, 4, 4));
        buttons.add(newChatBtn);
        buttons.add(deleteChatBtn);
        buttons.add(saveChatsBtn);
        panel.add(new JScrollPane(chatList), BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.SOUTH);
        return panel;
    }

    private JTabbedPane createWorkspace()
    {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Чат", createChatPanel());
        tabs.addTab("Настройки", createSettingsPanel());
        tabs.addTab("Файлы", createFilesPanel());
        return tabs;
    }

    private JPanel createChatPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        outputArea = new JTextArea(24, 80);
        outputArea.setLineWrap(true);
        outputArea.setWrapStyleWord(true);
        outputArea.setEditable(false);
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

    private JPanel createSettingsPanel()
    {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        systemPromptArea = new JTextArea(10, 80);
        systemPromptArea.setLineWrap(true);
        systemPromptArea.setWrapStyleWord(true);
        systemPromptArea.setText("Ты — ведущий Java-разработчик. Работаешь только с Claude Opus 4.7. Отвечай точно, проверяй код, предлагай минимальные рабочие правки.");
        JScrollPane systemScroll = new JScrollPane(systemPromptArea);
        systemScroll.setBorder(new TitledBorder("System prompt"));

        apiKeyField = new JPasswordField(52);
        baseUrlField = new JTextField(DEFAULT_BASE_URL, 52);
        maxOutputTokensField = new JTextField(String.valueOf(DEFAULT_OUTPUT_TOKENS), 12);
        maxOutputTokensField.setToolTipText("max_tokens — это лимит ответа, не размер контекста. 128000 возможно у Anthropic, но ProxyAPI может отказать по балансу; безопасно начинать с 4096–16000.");

        tokenInfoLabel = new JLabel(tokenText(0, 0));
        statusLabel = new JLabel("Статус: модель зафиксирована в клиенте: " + MODEL_NAME);
        keyLoadedLabel = new JLabel();
        keyCheckLabel = new JLabel();
        lastErrorLabel = new JLabel();
        balanceLabel = new JLabel("Баланс: не запрашивался");

        JPanel settings = new JPanel(new GridLayout(14, 1, 4, 4));
        settings.add(row("API key ProxyAPI:", apiKeyField));
        settings.add(row("Anthropic base URL:", baseUrlField));
        settings.add(row("max_tokens ответа:", maxOutputTokensField));
        settings.add(new JLabel("Контекст модели: до " + MAX_CONTEXT_TOKENS + " токенов; технический максимум ответа: " + MAX_OUTPUT_TOKENS + "; безопасно для ProxyAPI: " + DEFAULT_OUTPUT_TOKENS + "."));
        settings.add(new JLabel("Важно: max_tokens не расширяет контекст, а резервирует максимум ответа и может вызвать 402 при малом балансе."));
        settings.add(tokenInfoLabel);
        settings.add(statusLabel);
        settings.add(keyLoadedLabel);
        settings.add(keyCheckLabel);
        settings.add(lastErrorLabel);
        settings.add(balanceLabel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton saveBtn = new JButton("Сохранить настройки");
        saveBtn.addActionListener(e -> saveConfig());
        JButton initBtn = new JButton("Переинициализировать клиент");
        initBtn.addActionListener(e -> initializeClient(true));
        JButton safeMaxBtn = new JButton("Безопасный max_tokens = 4096");
        safeMaxBtn.addActionListener(e -> maxOutputTokensField.setText(String.valueOf(DEFAULT_OUTPUT_TOKENS)));
        JButton checkKeyBtn = new JButton("Проверить ключ");
        checkKeyBtn.addActionListener(e -> checkApiKey());
        JButton balanceBtn = new JButton("Обновить баланс");
        balanceBtn.addActionListener(e -> updateBalance());
        buttons.add(saveBtn);
        buttons.add(initBtn);
        buttons.add(safeMaxBtn);
        buttons.add(checkKeyBtn);
        buttons.add(balanceBtn);
        settings.add(buttons);

        panel.add(systemScroll, BorderLayout.CENTER);
        panel.add(settings, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createFilesPanel()
    {
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

        JButton addFilesButton = new JButton("Добавить файлы");
        addFilesButton.addActionListener(e -> chooseFiles());
        JButton removeFilesButton = new JButton("Удалить файл");
        removeFilesButton.addActionListener(e -> removeSelectedLocalFiles());
        JButton sendFilesButton = new JButton("Отправить файлы");
        sendFilesButton.addActionListener(e -> sendLocalFilesToModelArea());
        JButton downloadAiFilesButton = new JButton("Загрузить файлы");
        downloadAiFilesButton.addActionListener(e -> downloadAiFiles());

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

        c.gridy = 1;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 0.34;
        panel.add(wrap(new JScrollPane(localFilesList), "Локальные файлы"), c);

        c.gridy = 2;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weighty = 0;
        JPanel sendRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        sendRow.add(sendFilesButton);
        sendRow.add(new JLabel("Файлы будут приложены к следующему запросу в чат, но не будут показаны длинным текстом в истории."));
        panel.add(sendRow, c);

        c.gridy = 3;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 0.33;
        panel.add(wrap(new JScrollPane(uploadedFilesList), "Uploaded"), c);

        c.gridy = 4;
        c.fill = GridBagConstraints.BOTH;
        c.weighty = 0.33;
        panel.add(wrap(new JScrollPane(aiFilesList), "Файлы ИИ"), c);

        c.gridy = 5;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weighty = 0;
        JPanel bottomRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        bottomRow.add(downloadAiFilesButton);
        bottomRow.add(new JLabel("Если файлов несколько или есть структура папок, они сохранятся ZIP-архивом в «Загрузки»."));
        panel.add(bottomRow, c);

        return panel;
    }

    private JPanel wrap(java.awt.Component component, String title)
    {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new TitledBorder(title));
        p.add(component, BorderLayout.CENTER);
        return p;
    }

    private JPanel row(String label, java.awt.Component component)
    {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
        row.add(new JLabel(label));
        row.add(component);
        return row;
    }

    private void sendMessage()
    {
        if (client == null)
        {
            showError("Клиент не инициализирован. Укажи API key и нажми переинициализацию.");
            return;
        }
        String userText = inputArea.getText().trim();
        List<UserVisibleFile> uploadedFiles = modelToUserVisibleList(uploadedFilesModel);
        if (userText.isEmpty() && uploadedFiles.isEmpty()) return;
        if (currentChatName == null) createChat("Новый чат", true);

        String fileAppendix;
        try
        {
            fileAppendix = fileTransport.buildPromptAppendix(uploadedFiles);
        }
        catch (IOException ex)
        {
            showError("Не удалось подготовить файлы к запросу: " + ex.getMessage());
            return;
        }
        String fullUserMessage = userText + fileAppendix;
        String displayUserMessage = userText + buildUserFileSummary(uploadedFiles);
        List<MessageEntry> history = conversations.get(currentChatName);

        appendOutput("Вы", displayUserMessage);
        inputArea.setText("");
        setControlsEnabled(false);
        statusLabel.setText("Статус: запрос выполняется: " + MODEL_NAME);

        new SwingWorker<Message, Void>()
        {
            @Override
            protected Message doInBackground()
            {
                MessageCreateParams.Builder builder = MessageCreateParams.builder()
                        .model(Model.CLAUDE_OPUS_4_7)
                        .maxTokens(readMaxOutputTokens());

                String system = systemPromptArea.getText().trim() + "\n\n" + fileReturnInstruction();
                if (!system.isBlank()) builder.system(system);
                for (MessageEntry entry : history)
                {
                    if ("user".equals(entry.role)) builder.addUserMessage(entry.apiContent);
                    else if ("assistant".equals(entry.role)) builder.addAssistantMessage(entry.apiContent);
                }
                builder.addUserMessage(fullUserMessage);
                return client.messages().create(builder.build());
            }

            @Override
            protected void done()
            {
                try
                {
                    Message response = get();
                    String rawAnswer = extractText(response);
                    FileProcessingResult processingResult = fileTransport.extractGeneratedFiles(rawAnswer);
                    for (UserVisibleFile file : processingResult.generatedFiles())
                    {
                        aiFilesModel.addElement(file);
                    }
                    String visibleAnswer = processingResult.visibleAnswer().isBlank() ? rawAnswer : processingResult.visibleAnswer();
                    history.add(new MessageEntry("user", fullUserMessage, displayUserMessage));
                    history.add(new MessageEntry("assistant", rawAnswer, visibleAnswer));
                    appendOutput("Claude Opus 4.7", visibleAnswer);
                    long in = response.usage().inputTokens();
                    long out = response.usage().outputTokens();
                    totalInputTokens += in;
                    totalOutputTokens += out;
                    tokenInfoLabel.setText(tokenText(in, out));
                    saveChats();
                    uploadedFilesModel.clear();
                    statusLabel.setText("Статус: готово. Последний запрос: input " + in + ", output " + out + ".");
                    refreshSettingsIndicators("успешно", "-");
                }
                catch (Exception ex)
                {
                    appendOutput("Ошибка", rootMessage(ex));
                    statusLabel.setText("Статус: ошибка запроса");
                    refreshSettingsIndicators("ошибка", rootMessage(ex));
                }
                finally
                {
                    setControlsEnabled(true);
                }
            }
        }.execute();
    }

    private String fileReturnInstruction()
    {
        return "Если нужно вернуть пользователю файлы, не печатай их как обычный длинный текст. "
                + "Верни каждый файл строго отдельным блоком вида:\n"
                + "```file:relative/path/FileName.ext\n<полное содержимое файла>\n```\n"
                + "Для нескольких файлов сохраняй структуру папок в relative/path. Не используй file_id в ответе пользователю.";
    }

    private String buildUserFileSummary(List<UserVisibleFile> files)
    {
        if (files == null || files.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n[Файлы добавлены в запрос: ");
        for (int i = 0; i < files.size(); i++)
        {
            if (i > 0) sb.append(", ");
            sb.append(files.get(i).relativePath());
        }
        sb.append("]");
        return sb.toString();
    }

    private String extractText(Message response)
    {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content())
        {
            block.text().ifPresent(textBlock -> sb.append(textBlock.text()));
        }
        return sb.toString().isBlank() ? response.toString() : sb.toString();
    }

    private void chooseFiles()
    {
        JFileChooser chooser = new JFileChooser(Files.isDirectory(DEFAULT_PROJECT_JAVA_DIR) ? DEFAULT_PROJECT_JAVA_DIR.toFile() : downloadsDir().toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Файлы кода, текста и архивы", "java", "md", "txt", "zip", "xml", "properties", "json", "yml", "yaml", "sql", "gradle", "html", "css", "js", "ts"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
        {
            for (java.io.File file : chooser.getSelectedFiles())
            {
                Path path = file.toPath();
                localFilesModel.addElement(new UserVisibleFile(path.getFileName().toString(), path.getFileName().toString(), path, null, UserVisibleFile.SourceKind.LOCAL));
            }
        }
    }

    private void removeSelectedLocalFiles()
    {
        removeSelectedFromModel(localFilesList, localFilesModel);
    }

    private void sendLocalFilesToModelArea()
    {
        List<UserVisibleFile> selected = localFilesList.getSelectedValuesList();
        if (selected.isEmpty()) selected = modelToUserVisibleList(localFilesModel);
        if (selected.isEmpty()) return;
        try
        {
            List<Path> paths = selected.stream().map(UserVisibleFile::localPath).toList();
            List<UserVisibleFile> uploaded = fileTransport.prepareForModel(paths);
            for (UserVisibleFile file : uploaded)
            {
                uploadedFilesModel.addElement(file);
            }
            for (UserVisibleFile file : selected)
            {
                localFilesModel.removeElement(file);
            }
            statusLabel.setText("Статус: файлы подготовлены к отправке модели: " + uploaded.size());
        }
        catch (IOException ex)
        {
            showError("Не удалось подготовить файлы: " + ex.getMessage());
        }
    }

    private void downloadAiFiles()
    {
        List<UserVisibleFile> selected = aiFilesList.getSelectedValuesList();
        if (selected.isEmpty()) selected = modelToUserVisibleList(aiFilesModel);
        if (selected.isEmpty())
        {
            showError("Нет файлов ИИ для загрузки.");
            return;
        }
        try
        {
            Path savedTo = fileTransport.saveGeneratedFiles(selected, downloadsDir());
            statusLabel.setText("Статус: файлы ИИ сохранены: " + savedTo);
            JOptionPane.showMessageDialog(this, "Сохранено: " + savedTo);
        }
        catch (IOException ex)
        {
            showError("Не удалось сохранить файлы ИИ: " + ex.getMessage());
        }
    }

    private void removeSelectedFromModel(JList<UserVisibleFile> list, DefaultListModel<UserVisibleFile> model)
    {
        List<UserVisibleFile> selected = list.getSelectedValuesList();
        for (UserVisibleFile file : selected)
        {
            model.removeElement(file);
        }
    }

    private List<UserVisibleFile> modelToUserVisibleList(DefaultListModel<UserVisibleFile> model)
    {
        List<UserVisibleFile> result = new ArrayList<>();
        for (int i = 0; i < model.size(); i++)
        {
            result.add(model.get(i));
        }
        return result;
    }

    private Path downloadsDir()
    {
        Path downloads = Paths.get(System.getProperty("user.home"), "Downloads");
        return Files.isDirectory(downloads) ? downloads : Paths.get(System.getProperty("user.home"));
    }

    private void initializeClient(boolean showDialog)
    {
        String key = readApiKey();
        if (key.isBlank())
        {
            refreshSettingsIndicators("ошибка", "API key пустой");
            if (showDialog) showError("API key пустой.");
            return;
        }
        String baseUrl = baseUrlField.getText().isBlank() ? DEFAULT_BASE_URL : baseUrlField.getText().trim();
        client = AnthropicOkHttpClient.builder()
                .apiKey(key)
                .baseUrl(baseUrl)
                .build();
        statusLabel.setText("Статус: клиент инициализирован: " + MODEL_NAME + " через " + baseUrl);
        refreshSettingsIndicators("не проверялся", "-");
    }

    private void checkApiKey()
    {
        initializeClient(false);
        if (client == null) return;
        new SwingWorker<Message, Void>()
        {
            @Override
            protected Message doInBackground()
            {
                return client.messages().create(MessageCreateParams.builder()
                        .model(Model.CLAUDE_OPUS_4_7)
                        .maxTokens(16)
                        .addUserMessage("Ответь одним словом: OK")
                        .build());
            }

            @Override
            protected void done()
            {
                try
                {
                    Message msg = get();
                    refreshSettingsIndicators("успешно", "-");
                    statusLabel.setText("Статус: ключ активен, тестовый ответ получен: " + extractText(msg));
                }
                catch (Exception ex)
                {
                    refreshSettingsIndicators("ошибка", rootMessage(ex));
                    showError(rootMessage(ex));
                }
            }
        }.execute();
    }

    private void updateBalance()
    {
        new SwingWorker<String, Void>()
        {
            @Override
            protected String doInBackground() throws Exception
            {
                return new ClaudeFileService(readApiKey(), baseUrlField.getText()).readProxyBalance();
            }

            @Override
            protected void done()
            {
                try
                {
                    balanceLabel.setText("Баланс: " + get());
                }
                catch (Exception ex)
                {
                    balanceLabel.setText("Баланс: ошибка");
                    refreshSettingsIndicators("ошибка", rootMessage(ex));
                    showError(rootMessage(ex));
                }
            }
        }.execute();
    }

    private void refreshSettingsIndicators(String checkStatus, String lastError)
    {
        if (keyLoadedLabel != null) keyLoadedLabel.setText("Ключ загружен: " + (!readApiKey().isBlank() ? "да" : "нет"));
        if (keyCheckLabel != null) keyCheckLabel.setText("Проверка: " + checkStatus);
        if (lastErrorLabel != null) lastErrorLabel.setText("Последняя ошибка: " + (lastError == null || lastError.isBlank() ? "-" : lastError));
    }

    private long readMaxOutputTokens()
    {
        try
        {
            long value = Long.parseLong(maxOutputTokensField.getText().trim());
            return Math.max(1L, Math.min(value, MAX_OUTPUT_TOKENS));
        }
        catch (NumberFormatException ex)
        {
            return DEFAULT_OUTPUT_TOKENS;
        }
    }

    private String readApiKey()
    {
        return new String(apiKeyField.getPassword()).trim();
    }

    private void loadConfig()
    {
        if (!Files.exists(CONFIG_FILE)) return;
        Properties properties = new Properties();
        try (var in = Files.newInputStream(CONFIG_FILE))
        {
            properties.load(in);
            apiKeyField.setText(properties.getProperty("api_key", ""));
            baseUrlField.setText(properties.getProperty("base_url", DEFAULT_BASE_URL));
            String loadedMaxTokens = properties.getProperty("max_output_tokens", String.valueOf(DEFAULT_OUTPUT_TOKENS));
            try
            {
                long loaded = Long.parseLong(loadedMaxTokens.trim());
                if (loaded > SAFE_PROXY_OUTPUT_TOKENS)
                {
                    loadedMaxTokens = String.valueOf(DEFAULT_OUTPUT_TOKENS);
                    statusLabel.setText("Статус: max_tokens сброшен на 4096: слишком большой лимит может давать 402 в ProxyAPI.");
                }
            }
            catch (NumberFormatException ignored)
            {
                loadedMaxTokens = String.valueOf(DEFAULT_OUTPUT_TOKENS);
            }
            maxOutputTokensField.setText(loadedMaxTokens);
            systemPromptArea.setText(properties.getProperty("system_prompt", systemPromptArea.getText()));
        }
        catch (IOException ex)
        {
            statusLabel.setText("Статус: не удалось прочитать config.properties: " + ex.getMessage());
        }
    }

    private void saveConfig()
    {
        try
        {
            Files.createDirectories(CONFIG_DIR);
            Properties properties = new Properties();
            properties.setProperty("api_key", readApiKey());
            properties.setProperty("base_url", baseUrlField.getText().trim());
            properties.setProperty("max_output_tokens", String.valueOf(readMaxOutputTokens()));
            properties.setProperty("system_prompt", systemPromptArea.getText());
            try (var out = Files.newOutputStream(CONFIG_FILE))
            {
                properties.store(out, "Claude Opus desktop client runtime config");
            }
            initializeClient(false);
            refreshSettingsIndicators("не проверялся", "-");
            JOptionPane.showMessageDialog(this, "Настройки сохранены в " + CONFIG_FILE);
        }
        catch (IOException ex)
        {
            refreshSettingsIndicators("ошибка", ex.getMessage());
            showError("Не удалось сохранить настройки: " + ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void loadChats()
    {
        if (!Files.exists(CHATS_FILE)) return;
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(CHATS_FILE)))
        {
            Object object = input.readObject();
            if (object instanceof Map<?, ?> map)
            {
                conversations.clear();
                for (Map.Entry<?, ?> entry : map.entrySet())
                {
                    conversations.put(String.valueOf(entry.getKey()), (List<MessageEntry>) entry.getValue());
                }
                refreshChatList();
            }
        }
        catch (Exception ex)
        {
            statusLabel.setText("Статус: историю чатов не удалось загрузить: " + ex.getMessage());
        }
    }

    private void saveChats()
    {
        try
        {
            Files.createDirectories(CONFIG_DIR);
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(CHATS_FILE)))
            {
                output.writeObject(conversations);
            }
        }
        catch (IOException ex)
        {
            statusLabel.setText("Статус: историю чатов не удалось сохранить: " + ex.getMessage());
        }
    }

    private void createChatFromDialog()
    {
        String name = JOptionPane.showInputDialog(this, "Название чата:", "Чат " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        if (name != null && !name.isBlank()) createChat(name.trim(), true);
    }

    private void createChat(String name, boolean select)
    {
        String unique = name;
        int i = 2;
        while (conversations.containsKey(unique))
        {
            unique = name + " " + i++;
        }
        conversations.put(unique, new ArrayList<>());
        chatListModel.addElement(unique);
        if (select) chatList.setSelectedValue(unique, true);
        if (currentChatName == null)
        {
            currentChatName = unique;
            chatList.setSelectedValue(unique, true);
        }
        saveChats();
    }

    private void refreshChatList()
    {
        chatListModel.clear();
        for (String name : conversations.keySet())
        {
            chatListModel.addElement(name);
        }
        if (!conversations.isEmpty()) chatList.setSelectedIndex(0);
    }

    private void deleteSelectedChat()
    {
        String selected = chatList.getSelectedValue();
        if (selected == null) return;
        conversations.remove(selected);
        chatListModel.removeElement(selected);
        if (conversations.isEmpty()) createChat("Новый чат", true);
        saveChats();
    }

    private void loadChat(String chatName)
    {
        if (chatName == null || !conversations.containsKey(chatName)) return;
        currentChatName = chatName;
        outputArea.setText("");
        for (MessageEntry entry : conversations.get(chatName))
        {
            appendOutput("assistant".equals(entry.role) ? "Claude Opus 4.7" : "Вы", entry.displayContent == null ? entry.apiContent : entry.displayContent);
        }
    }

    private void appendOutput(String who, String text)
    {
        outputArea.append("\n=== " + who + " ===\n");
        outputArea.append(text == null ? "" : text);
        outputArea.append("\n");
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }

    private String tokenText(long lastInput, long lastOutput)
    {
        return "Последний запрос: input " + lastInput + " / output " + lastOutput
                + " | Сессия: input " + totalInputTokens + " / output " + totalOutputTokens
                + " | Модель: " + MODEL_NAME
                + " | Контекст до " + MAX_CONTEXT_TOKENS + " | max output до " + MAX_OUTPUT_TOKENS;
    }

    private void saveOutputToFile()
    {
        JFileChooser chooser = new JFileChooser(downloadsDir().toFile());
        chooser.setSelectedFile(new java.io.File("claude-chat-history.md"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION)
        {
            try
            {
                Files.writeString(chooser.getSelectedFile().toPath(), outputArea.getText(), StandardCharsets.UTF_8);
                statusLabel.setText("Статус: сохранено: " + chooser.getSelectedFile());
            }
            catch (IOException ex)
            {
                showError("Не удалось сохранить файл: " + ex.getMessage());
            }
        }
    }

    private void setControlsEnabled(boolean enabled)
    {
        inputArea.setEnabled(enabled);
    }

    private void showError(String message)
    {
        JOptionPane.showMessageDialog(this, message, "Ошибка", JOptionPane.ERROR_MESSAGE);
    }

    private String rootMessage(Throwable ex)
    {
        Throwable current = ex;
        while (current.getCause() != null)
        {
            current = current.getCause();
        }
        String message = current.getMessage() == null ? current.toString() : current.getMessage();
        if (message.contains("Files API") && message.contains("HTTP 404"))
        {
            return message + "\n\nВероятная причина: текущий ProxyAPI endpoint не поддерживает Anthropic Files API /v1/files. "
                    + "Вкладка «Файлы» теперь отделена от способа доставки: пользовательский интерфейс остаётся тем же, а транспорт можно заменить позже.";
        }
        if (message.contains("Insufficient balance"))
        {
            return message + "\n\nВероятная причина: слишком большой max_tokens. Это лимит максимального ответа, а не контекста. "
                    + "Поставь в Настройках max_tokens=4096 или 8192 и повтори.";
        }
        return message;
    }

    public static class MessageEntry implements Serializable
    {
        @Serial
        private static final long serialVersionUID = 2L;
        public String role;
        public String apiContent;
        public String displayContent;

        public MessageEntry(String role, String apiContent, String displayContent)
        {
            this.role = role;
            this.apiContent = apiContent;
            this.displayContent = displayContent;
        }
    }

    public static void main(String[] args)
    {
        SwingUtilities.invokeLater(() -> new ClaudeDesktopClient().setVisible(true));
    }
}
