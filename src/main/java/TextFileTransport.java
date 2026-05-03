import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class TextFileTransport implements FileTransport
{
    public static final int MAX_INLINE_FILES = 50;
    public static final long MAX_INLINE_FILE_BYTES = 64L * 1024L;
    public static final long MAX_INLINE_TOTAL_BYTES = 2L * 1024L * 1024L;

    private static final Pattern FILE_BLOCK_PATTERN = Pattern.compile(
            "(?s)```(?:file|FILE)\\s*:?\\s*([^\\n`]+)\\R(.*?)```"
    );

    @Override
    public List<UserVisibleFile> prepareForModel(List<Path> localFiles) throws IOException
    {
        List<UserVisibleFile> result = new ArrayList<>();
        for (Path path : localFiles)
        {
            if (Files.isRegularFile(path))
            {
                result.add(new UserVisibleFile(path.getFileName().toString(), path.getFileName().toString(), path, null, UserVisibleFile.SourceKind.UPLOADED_TO_MODEL));
            }
        }
        return result;
    }

    @Override
    public String buildPromptAppendix(List<UserVisibleFile> uploadedFiles) throws IOException
    {
        if (uploadedFiles == null || uploadedFiles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n");
        sb.append("В запрос приложены файлы проекта. Это не обычный текст пользователя, а содержимое файлов. ");
        sb.append("Сохраняй имена и относительные пути файлов. Если возвращаешь новые или изменённые файлы, используй только формат:\n");
        sb.append("```file:relative/path/FileName.ext\n<полное содержимое файла>\n```\n");
        sb.append("Не вставляй файлы обычным текстом вне таких блоков.\n");

        Counter counter = new Counter();
        for (UserVisibleFile file : uploadedFiles)
        {
            if (counter.count >= MAX_INLINE_FILES || counter.totalBytes >= MAX_INLINE_TOTAL_BYTES) break;
            Path path = file.localPath();
            if (path == null || !Files.isRegularFile(path)) continue;
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip"))
            {
                appendZip(sb, path, counter);
            }
            else
            {
                appendSinglePath(sb, path, path.getFileName().toString(), counter);
            }
        }
        sb.append("\n---\n");
        return sb.toString();
    }

    @Override
    public FileProcessingResult extractGeneratedFiles(String modelAnswer)
    {
        if (modelAnswer == null || modelAnswer.isBlank())
        {
            return new FileProcessingResult("", List.of());
        }
        List<UserVisibleFile> files = new ArrayList<>();
        Matcher matcher = FILE_BLOCK_PATTERN.matcher(modelAnswer);
        StringBuffer visible = new StringBuffer();
        while (matcher.find())
        {
            String rawPath = sanitizeRelativePath(matcher.group(1).trim());
            String content = matcher.group(2);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            files.add(new UserVisibleFile(rawPath, rawPath, null, bytes, UserVisibleFile.SourceKind.GENERATED_BY_AI));
            matcher.appendReplacement(visible, Matcher.quoteReplacement("\n[Файл ИИ: " + rawPath + "]\n"));
        }
        matcher.appendTail(visible);
        String cleaned = visible.toString().trim();
        if (!files.isEmpty())
        {
            cleaned += "\n\n[Получены файлы ИИ: " + files.size() + ". Открой вкладку «Файлы» и нажми «Загрузить файлы».]";
        }
        return new FileProcessingResult(cleaned, files);
    }

    @Override
    public Path saveGeneratedFiles(List<UserVisibleFile> files, Path downloadsDir) throws IOException
    {
        if (files == null || files.isEmpty()) throw new IOException("Нет файлов ИИ для загрузки.");
        Files.createDirectories(downloadsDir);
        if (files.size() == 1 && !hasNestedPath(files.get(0).relativePath()))
        {
            UserVisibleFile file = files.get(0);
            Path target = unique(downloadsDir.resolve(Path.of(file.relativePath()).getFileName()));
            Files.write(target, file.content());
            return target;
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path zip = unique(downloadsDir.resolve("ai-files-" + stamp + ".zip"));
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip)))
        {
            for (UserVisibleFile file : files)
            {
                String entryName = sanitizeRelativePath(file.relativePath());
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                zos.write(file.content());
                zos.closeEntry();
            }
        }
        return zip;
    }

    private void appendSinglePath(StringBuilder sb, Path path, String name, Counter counter) throws IOException
    {
        if (!isSupportedTextFile(name))
        {
            sb.append("\n[Файл пропущен: ").append(name).append(" — неподдерживаемый тип для текстового транспорта]\n");
            return;
        }
        byte[] bytes = Files.readAllBytes(path);
        appendOneFile(sb, name, bytes, counter);
    }

    private void appendZip(StringBuilder sb, Path zipPath, Counter counter) throws IOException
    {
        byte[] zipBytes = Files.readAllBytes(zipPath);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes)))
        {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null && counter.count < MAX_INLINE_FILES && counter.totalBytes < MAX_INLINE_TOTAL_BYTES)
            {
                if (entry.isDirectory()) continue;
                String name = zipPath.getFileName() + "!/" + entry.getName();
                if (!isSupportedTextFile(entry.getName()))
                {
                    sb.append("\n[Zip-entry пропущен: ").append(name).append(" — неподдерживаемый тип]\n");
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                zis.transferTo(bos);
                appendOneFile(sb, name, bos.toByteArray(), counter);
            }
        }
    }

    private void appendOneFile(StringBuilder sb, String relativePath, byte[] bytes, Counter counter)
    {
        if (bytes.length > MAX_INLINE_FILE_BYTES)
        {
            sb.append("\n[Файл пропущен: ").append(relativePath).append(" — больше ").append(MAX_INLINE_FILE_BYTES).append(" байт]\n");
            return;
        }
        if (counter.count >= MAX_INLINE_FILES || counter.totalBytes + bytes.length > MAX_INLINE_TOTAL_BYTES)
        {
            sb.append("\n[Файл пропущен: ").append(relativePath).append(" — превышен общий лимит вложений]\n");
            return;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        sb.append("\n### FILE: ").append(sanitizeRelativePath(relativePath)).append("\n");
        sb.append("```\n").append(content).append("\n```\n");
        counter.count++;
        counter.totalBytes += bytes.length;
    }

    private boolean isSupportedTextFile(String name)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".md") || lower.endsWith(".txt")
                || lower.endsWith(".xml") || lower.endsWith(".properties") || lower.endsWith(".json")
                || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".sql")
                || lower.endsWith(".gradle") || lower.endsWith(".html") || lower.endsWith(".css")
                || lower.endsWith(".js") || lower.endsWith(".ts");
    }

    private boolean hasNestedPath(String relativePath)
    {
        return relativePath.contains("/") || relativePath.contains("\\");
    }

    private String sanitizeRelativePath(String path)
    {
        String normalized = path.replace('\\', '/').replaceAll("^/+", "");
        while (normalized.contains("../"))
        {
            normalized = normalized.replace("../", "");
        }
        if (normalized.isBlank()) return "generated.txt";
        return normalized;
    }

    private Path unique(Path path) throws IOException
    {
        if (!Files.exists(path)) return path;
        String fileName = path.getFileName().toString();
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0)
        {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int i = 2; i < 10_000; i++)
        {
            Path candidate = path.getParent().resolve(base + "-" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("Не удалось подобрать свободное имя файла: " + path);
    }

    private static class Counter
    {
        int count;
        long totalBytes;
    }
}
