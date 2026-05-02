import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;

import javax.swing.BorderFactory;
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
import java.awt.GridLayout;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ClaudeDesktopClient extends JFrame
{
    private static final String APP_TITLE = "Claude Opus 4.7 Desktop Client";
    private static final String MODEL_NAME = "claude-opus-4-7";
    private static final long MAX_CONTEXT_TOKENS = 1_000_000L;
    private static final long MAX_OUTPUT_TOKENS = 128_000L;
    private static final long DEFAULT_OUTPUT_TOKENS = 4_096L;
    private static final long SAFE_PROXY_OUTPUT_TOKENS = 32_000L;
    private static final int MAX_INLINE_FILES = 50;
    private static final long MAX_INLINE_FILE_BYTES = 64L * 1024L;
    private static final long MAX_INLINE_TOTAL_BYTES = 2L * 1024L * 1024L;
    private static final String DEFAULT_BASE_URL = ClaudeFileService.DEFAULT_PROXY_BASE_URL;
    private static final Path CONFIG_DIR = Paths.get(System.getProperty("user.home"), ".claude-opus-client");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.properties");
    private static final Path CHATS_FILE = CONFIG_DIR.resolve("chats.ser");

    private JTextArea inputArea;
    private JTextArea outputArea;
    private JTextArea systemPromptArea;
    private JPasswordField apiKeyField;
    private JTextField baseUrlField;
    private JTextField maxOutputTokensField;
    private JTextField generatedFileIdField;

    private JLabel tokenInfoLabel;
    private JLabel statusLabel;
    private JLabel keyLoadedLabel;
    private JLabel keyCheckLabel;
    private JLabel lastErrorLabel;
    private JLabel balanceLabel;

    private JList<String> chatList;
    private DefaultListModel<String> chatListModel;
    private DefaultListModel<String> attachmentsModel;
    private JList<String> attachmentsList;
    private DefaultListModel<String> uploadedFileIdsModel;
    private DefaultListModel<String> generatedFileIdsModel;
    private JList<String> uploadedFileIdsList;
    private JList<String> generatedFileIdsList;

    private final Map<String, List<MessageEntry>> conversations = new LinkedHashMap<>();
    private final List<Path> attachedFiles = new ArrayList<>();
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

        JButton sendButton = new JButton("Отправить обычный запрос");
        sendButton.addActionListener(e -> sendMessage());
        JButton saveAnswerButton = new JButton("Сохранить ответ в файл");
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
        statusLabel = new JLabel("Статус: модель зафиксирована: " + MODEL_NAME);
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
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        attachmentsModel = new DefaultListModel<>();
        attachmentsList = new JList<>(attachmentsModel);
        attachmentsList.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        uploadedFileIdsModel = new DefaultListModel<>();
        uploadedFileIdsList = new JList<>(uploadedFileIdsModel);
        generatedFileIdsModel = new DefaultListModel<>();
        generatedFileIdsList = new JList<>(generatedFileIdsModel);
        generatedFileIdField = new JTextField(32);

        JTextArea help = new JTextArea();
        help.setEditable(false);
        help.setLineWrap(true);
        help.setWrapStyleWord(true);
        help.setText("Режим 2: 1) добавь файл, 2) нажми 'Upload выбранный -> file_id', 3) напиши задачу в поле ввода на вкладке Чат, "
                + "4) нажми 'Code execution -> generated file_id', 5) выбери найденный file_id и скачай. Если ProxyAPI вернул 404 на /v1/files — этот режим через ProxyAPI сейчас недоступен; нужен прямой Anthropic API или поддержка beta Files API у прокси.");

        JButton addFiles = new JButton("Добавить .java/.md/.txt/.zip");
        addFiles.addActionListener(e -> chooseFiles());
        JButton remove = new JButton("Убрать выбранный");
        remove.addActionListener(e -> removeSelectedAttachment());
        JButton clear = new JButton("Очистить список");
        clear.addActionListener(e -> clearAttachments());
        JButton upload = new JButton("Upload выбранный -> file_id (может не работать в ProxyAPI)");
        upload.addActionListener(e -> uploadSelectedFile());
        JButton codeExec = new JButton("Code execution -> generated file_id");
        codeExec.addActionListener(e -> runCodeExecutionForFiles());
        JButton downloadSelected = new JButton("Скачать выбранный generated file_id");
        downloadSelected.addActionListener(e -> downloadSelectedGeneratedFile());
        JButton downloadTyped = new JButton("Скачать file_id из поля");
        downloadTyped.addActionListener(e -> downloadTypedFileId());
        JButton list = new JButton("Показать файлы Files API");
        list.addActionListener(e -> listRemoteFiles());

        JPanel buttons = new JPanel(new GridLayout(8, 1, 4, 4));
        buttons.add(addFiles);
        buttons.add(remove);
        buttons.add(clear);
        buttons.add(upload);
        buttons.add(codeExec);
        buttons.add(downloadSelected);
        buttons.add(downloadTyped);
        buttons.add(list);

        JPanel center = new JPanel(new GridLayout(1, 3, 6, 6));
        center.add(wrap(new JScrollPane(attachmentsList), "Локальные файлы"));
        center.add(wrap(new JScrollPane(uploadedFileIdsList), "Uploaded file_id для container_upload"));
        center.add(wrap(new JScrollPane(generatedFileIdsList), "Generated downloadable file_id"));

        JPanel south = new JPanel(new BorderLayout(4, 4));
        south.add(row("file_id вручную:", generatedFileIdField), BorderLayout.NORTH);
        south.add(new JScrollPane(help), BorderLayout.CENTER);

        panel.add(center, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.EAST);
        panel.add(south, BorderLayout.SOUTH);
        panel.setBorder(new TitledBorder("Файлы / Files API / code execution"));
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
        if (userText.isEmpty() && attachedFiles.isEmpty()) return;
        if (currentChatName == null) createChat("Новый чат", true);

        String attachmentContext;
        try
        {
            attachmentContext = buildAttachmentContext();
        }
        catch (IOException ex)
        {
            showError("Не удалось прочитать вложения: " + ex.getMessage());
            return;
        }
        String fullUserMessage = userText + attachmentContext;
        List<MessageEntry> history = conversations.get(currentChatName);

        appendOutput("Вы", userText + (attachmentContext.isBlank() ? "" : "\n\n[Вложенные файлы добавлены в запрос]"));
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

                String system = systemPromptArea.getText().trim();
                if (!system.isBlank()) builder.system(system);
                for (MessageEntry entry : history)
                {
                    if ("user".equals(entry.role)) builder.addUserMessage(entry.content);
                    else if ("assistant".equals(entry.role)) builder.addAssistantMessage(entry.content);
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
                    String answer = extractText(response);
                    history.add(new MessageEntry("user", fullUserMessage));
                    history.add(new MessageEntry("assistant", answer));
                    appendOutput("Claude Opus 4.7", answer);
                    long in = response.usage().inputTokens();
                    long out = response.usage().outputTokens();
                    totalInputTokens += in;
                    totalOutputTokens += out;
                    tokenInfoLabel.setText(tokenText(in, out));
                    saveChats();
                    clearAttachments();
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

    private String extractText(Message response)
    {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content())
        {
            block.text().ifPresent(textBlock -> sb.append(textBlock.text()));
        }
        return sb.toString().isBlank() ? response.toString() : sb.toString();
    }

    private void runCodeExecutionForFiles()
    {
        String prompt = inputArea.getText().trim();
        if (prompt.isBlank())
        {
            showError("На вкладке Чат напиши задачу: какие файлы Claude должен создать.");
            return;
        }
        setControlsEnabled(false);
        statusLabel.setText("Статус: code execution запрос выполняется");
        List<String> uploadedIds = modelToList(uploadedFileIdsModel);
        new SwingWorker<String, Void>()
        {
            @Override
            protected String doInBackground() throws Exception
            {
                return new ClaudeFileService(readApiKey(), baseUrlField.getText())
                        .createMessageWithCodeExecution(prompt, uploadedIds, readMaxOutputTokens());
            }

            @Override
            protected void done()
            {
                try
                {
                    String json = get();
                    appendOutput("Code execution raw response", json);
                    List<String> ids = ClaudeFileService.extractFileIds(json);
                    generatedFileIdsModel.clear();
                    for (String id : ids)
                    {
                        generatedFileIdsModel.addElement(id);
                    }
                    if (!ids.isEmpty()) generatedFileIdField.setText(ids.get(ids.size() - 1));
                    statusLabel.setText("Статус: code execution готово, найдено file_id: " + ids.size());
                    refreshSettingsIndicators("успешно", "-");
                }
                catch (Exception ex)
                {
                    statusLabel.setText("Статус: ошибка code execution");
                    refreshSettingsIndicators("ошибка", rootMessage(ex));
                    showError(rootMessage(ex));
                }
                finally
                {
                    setControlsEnabled(true);
                }
            }
        }.execute();
    }

    private List<String> modelToList(DefaultListModel<String> model)
    {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < model.size(); i++)
        {
            result.add(model.get(i));
        }
        return result;
    }

    private String buildAttachmentContext() throws IOException
    {
        if (attachedFiles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n---\nВложенные файлы для анализа. Сохраняй имена файлов и учитывай их как часть контекста.\n");
        long totalBytes = 0L;
        int count = 0;
        for (Path path : attachedFiles)
        {
            if (count >= MAX_INLINE_FILES || totalBytes >= MAX_INLINE_TOTAL_BYTES) break;
            if (path.toString().toLowerCase().endsWith(".zip"))
            {
                InlineResult result = appendZipEntries(sb, path, count, totalBytes);
                count = result.count;
                totalBytes = result.totalBytes;
            }
            else if (isInlineTextFile(path.getFileName().toString()))
            {
                byte[] bytes = Files.readAllBytes(path);
                if (bytes.length > MAX_INLINE_FILE_BYTES)
                {
                    sb.append("\n[Пропущен файл ").append(path.getFileName()).append(": больше ").append(MAX_INLINE_FILE_BYTES).append(" байт]\n");
                    continue;
                }
                appendOneFile(sb, path.getFileName().toString(), new String(bytes, StandardCharsets.UTF_8));
                totalBytes += bytes.length;
                count++;
            }
            else
            {
                sb.append("\n[Пропущен неподдерживаемый для inline файл: ").append(path.getFileName()).append("]\n");
            }
        }
        sb.append("\n---\n");
        return sb.toString();
    }

    private InlineResult appendZipEntries(StringBuilder sb, Path zipPath, int count, long totalBytes) throws IOException
    {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(zipPath))))
        {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null && count < MAX_INLINE_FILES && totalBytes < MAX_INLINE_TOTAL_BYTES)
            {
                if (entry.isDirectory() || !isInlineTextFile(entry.getName())) continue;
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                zis.transferTo(bos);
                byte[] bytes = bos.toByteArray();
                if (bytes.length > MAX_INLINE_FILE_BYTES)
                {
                    sb.append("\n[Пропущен zip-entry ").append(entry.getName()).append(": больше лимита]\n");
                    continue;
                }
                appendOneFile(sb, zipPath.getFileName() + "!/" + entry.getName(), new String(bytes, StandardCharsets.UTF_8));
                totalBytes += bytes.length;
                count++;
            }
        }
        return new InlineResult(count, totalBytes);
    }

    private void appendOneFile(StringBuilder sb, String fileName, String content)
    {
        sb.append("\n### FILE: ").append(fileName).append("\n```\n").append(content).append("\n```\n");
    }

    private boolean isInlineTextFile(String name)
    {
        String lower = name.toLowerCase();
        return lower.endsWith(".java") || lower.endsWith(".md") || lower.endsWith(".txt")
                || lower.endsWith(".xml") || lower.endsWith(".properties") || lower.endsWith(".json")
                || lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    private void chooseFiles()
    {
        JFileChooser chooser = new JFileChooser(Paths.get("C:/Users/Alexey Tkachev/Documents/IdeaProjects/ClaudeClient2/src/main/java").toFile());
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Code/text/archive", "java", "md", "txt", "zip", "xml", "properties", "json", "yml", "yaml"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
        {
            for (java.io.File file : chooser.getSelectedFiles())
            {
                Path path = file.toPath();
                attachedFiles.add(path);
                attachmentsModel.addElement(path.toString());
            }
        }
    }

    private void removeSelectedAttachment()
    {
        int idx = attachmentsList.getSelectedIndex();
        if (idx >= 0)
        {
            attachedFiles.remove(idx);
            attachmentsModel.remove(idx);
        }
    }

    private void clearAttachments()
    {
        attachedFiles.clear();
        if (attachmentsModel != null) attachmentsModel.clear();
    }

    private void uploadSelectedFile()
    {
        int idx = attachmentsList.getSelectedIndex();
        if (idx < 0)
        {
            showError("Выбери файл в списке локальных файлов.");
            return;
        }
        Path file = attachedFiles.get(idx);
        new SwingWorker<String, Void>()
        {
            @Override
            protected String doInBackground() throws Exception
            {
                return new ClaudeFileService(readApiKey(), baseUrlField.getText()).uploadFile(file);
            }

            @Override
            protected void done()
            {
                try
                {
                    String json = get();
                    String id = ClaudeFileService.extractUploadedFileId(json);
                    if (!id.isBlank()) uploadedFileIdsModel.addElement(id);
                    appendOutput("Files API upload response", json);
                    statusLabel.setText(id.isBlank() ? "Статус: upload выполнен, file_id не найден" : "Статус: upload выполнен: " + id);
                }
                catch (Exception ex)
                {
                    refreshSettingsIndicators("ошибка", rootMessage(ex));
                    showError(rootMessage(ex));
                }
            }
        }.execute();
    }

    private void listRemoteFiles()
    {
        new SwingWorker<String, Void>()
        {
            @Override
            protected String doInBackground() throws Exception
            {
                return new ClaudeFileService(readApiKey(), baseUrlField.getText()).listFiles();
            }

            @Override
            protected void done()
            {
                try
                {
                    outputArea.append("\n[Files API list]\n" + get() + "\n");
                }
                catch (Exception ex)
                {
                    refreshSettingsIndicators("ошибка", rootMessage(ex));
                    showError(rootMessage(ex));
                }
            }
        }.execute();
    }

    private void downloadSelectedGeneratedFile()
    {
        String id = generatedFileIdsList.getSelectedValue();
        if (id == null || id.isBlank()) id = generatedFileIdField.getText().trim();
        downloadFileId(id);
    }

    private void downloadTypedFileId()
    {
        downloadFileId(generatedFileIdField.getText().trim());
    }

    private void downloadFileId(String fileId)
    {
        if (fileId == null || fileId.isBlank())
        {
            showError("Укажи file_id.");
            return;
        }
        JFileChooser chooser = new JFileChooser(downloadsDir().toFile());
        chooser.setSelectedFile(new java.io.File(fileId + ".bin"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path saveTo = chooser.getSelectedFile().toPath();
        new SwingWorker<Void, Void>()
        {
            @Override
            protected Void doInBackground() throws Exception
            {
                new ClaudeFileService(readApiKey(), baseUrlField.getText()).downloadGeneratedFile(fileId, saveTo);
                return null;
            }

            @Override
            protected void done()
            {
                try
                {
                    get();
                    statusLabel.setText("Статус: файл скачан: " + saveTo);
                }
                catch (Exception ex)
                {
                    refreshSettingsIndicators("ошибка", rootMessage(ex));
                    showError(rootMessage(ex));
                }
            }
        }.execute();
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
            appendOutput("assistant".equals(entry.role) ? "Claude Opus 4.7" : "Вы", entry.content);
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
        chooser.setSelectedFile(new java.io.File("claude-answer.md"));
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
                    + "Обычный /v1/messages работает, но режим upload/download через file_id требует поддержки beta Files API на стороне прокси "
                    + "или прямого Anthropic API base URL.";
        }
        if (message.contains("Insufficient balance"))
        {
            return message + "\n\nВероятная причина: слишком большой max_tokens. Это лимит максимального ответа, а не контекста. "
                    + "При max_tokens=128000 прокси может предварительно резервировать стоимость большого ответа/контекста и отклонять запрос. "
                    + "Поставь в Настройках max_tokens=4096 или 8192 и повтори.";
        }
        return message;
    }

    private record InlineResult(int count, long totalBytes)
    {
    }

    public static class MessageEntry implements Serializable
    {
        @Serial
        private static final long serialVersionUID = 1L;
        public String role;
        public String content;

        public MessageEntry(String role, String content)
        {
            this.role = role;
            this.content = content;
        }
    }

    public static void main(String[] args)
    {
        SwingUtilities.invokeLater(() -> new ClaudeDesktopClient().setVisible(true));
    }
}
