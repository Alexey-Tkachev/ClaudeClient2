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

public class TextFileTransport implements FileTransport {
    public static final int MAX_INLINE_FILES = 50;
    public static final long MAX_INLINE_FILE_BYTES = 64L * 1024L;
    public static final long MAX_INLINE_TOTAL_BYTES = 2L * 1024L * 1024L;

    private static final Pattern FILE_BLOCK_PATTERN = Pattern.compile(
            "(?s)```(?:file|FILE)\\s*:?\\s*([^\\n`]+)\\R(.*?)```"
    );
    private static final Pattern JAVA_PACKAGE_PATTERN = Pattern.compile("(?m)^\\s*package\\s+([a-zA-Z_][\\w]*(?:\\.[a-zA-Z_][\\w]*)*)\\s*;");

    @Override
    public List<UserVisibleFile> prepareForModel(List<Path> localFiles) throws IOException {
        List<UserVisibleFile> result = new ArrayList<>();
        for (Path path : localFiles) {
            if (Files.isRegularFile(path)) {
                String relative = inferRelativePath(path);
                result.add(new UserVisibleFile(path.getFileName().toString(), relative, path, null, UserVisibleFile.SourceKind.UPLOADED_TO_MODEL));
            }
        }
        return result;
    }

    @Override
    public String buildPromptAppendix(List<UserVisibleFile> uploadedFiles) throws IOException {
        if (uploadedFiles == null || uploadedFiles.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n");
        sb.append("В запрос приложены файлы проекта. Это не обычный текст пользователя, а содержимое файлов. ");
        sb.append("Сохраняй имена, пакеты и относительные пути файлов. Если возвращаешь новые или изменённые файлы, используй только формат:\n");
        sb.append("```file:relative/path/FileName.ext\n<полное содержимое файла>\n```\n");
        sb.append("Для Java-классов сохраняй package. Не вставляй файлы обычным текстом вне таких блоков.\n");

        Counter counter = new Counter();
        for (UserVisibleFile file : uploadedFiles) {
            if (counter.count >= MAX_INLINE_FILES || counter.totalBytes >= MAX_INLINE_TOTAL_BYTES) break;
            Path path = file.localPath();
            if (path == null || !Files.isRegularFile(path)) continue;
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".zip")) {
                appendZip(sb, path, counter);
            } else {
                appendSinglePath(sb, path, file.relativePath(), counter);
            }
        }
        sb.append("\n---\n");
        return sb.toString();
    }

    @Override
    public FileProcessingResult extractGeneratedFiles(String modelAnswer, String sourceRoot) {
        if (modelAnswer == null || modelAnswer.isBlank()) {
            return new FileProcessingResult("", List.of());
        }
        List<UserVisibleFile> files = new ArrayList<>();
        Matcher matcher = FILE_BLOCK_PATTERN.matcher(modelAnswer);
        StringBuffer visible = new StringBuffer();
        while (matcher.find()) {
            String rawPath = sanitizeRelativePath(matcher.group(1).trim());
            String content = matcher.group(2);
            String finalPath = pathForGeneratedFile(rawPath, content, sourceRoot);
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            files.add(new UserVisibleFile(fileNameOnly(finalPath), finalPath, null, bytes, UserVisibleFile.SourceKind.GENERATED_BY_AI));
            matcher.appendReplacement(visible, Matcher.quoteReplacement(""));
        }
        matcher.appendTail(visible);
        String cleaned = visible.toString().trim();
        if (!files.isEmpty()) {
            StringBuilder summary = new StringBuilder();
            if (!cleaned.isBlank()) summary.append("\n\n");
            summary.append("Получены файлы ИИ:\n");
            for (int i = 0; i < files.size(); i++) {
                summary.append(i + 1).append(". ")
                        .append(files.get(i).displayPathFromSrc())
                        .append(" — ")
                        .append(files.get(i).sizeLabel())
                        .append("\n");
            }
            summary.append("Открой вкладку «Файлы» и нажми «Загрузить файлы».");
            cleaned += summary;
        }
        return new FileProcessingResult(cleaned, files);
    }

    @Override
    public Path saveGeneratedFiles(List<UserVisibleFile> files, Path downloadsDir, String modelDisplayName, String chatName, String sourceRoot) throws IOException {
        if (files == null || files.isEmpty()) throw new IOException("Нет файлов ИИ для загрузки.");
        Files.createDirectories(downloadsDir);
        if (files.size() == 1 && !hasNestedPath(files.get(0).relativePath())) {
            UserVisibleFile file = files.get(0);
            Path target = unique(downloadsDir.resolve(Path.of(file.relativePath()).getFileName()));
            Files.write(target, file.content());
            return target;
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm"));
        String archiveName = safeFileName(modelDisplayName) + "_" + safeFileName(chatName) + "_" + stamp + ".zip";
        Path zip = unique(downloadsDir.resolve(archiveName));
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (UserVisibleFile file : files) {
                String entryName = sanitizeRelativePath(pathForGeneratedFile(file.relativePath(), new String(file.content(), StandardCharsets.UTF_8), sourceRoot));
                ZipEntry entry = new ZipEntry(entryName);
                zos.putNextEntry(entry);
                zos.write(file.content());
                zos.closeEntry();
            }
        }
        return zip;
    }

    private void appendSinglePath(StringBuilder sb, Path path, String relativePath, Counter counter) throws IOException {
        if (!isSupportedTextFile(path.getFileName().toString())) {
            sb.append("\n[Файл пропущен: ").append(path.getFileName()).append(" — неподдерживаемый тип для текстового транспорта]\n");
            return;
        }
        byte[] bytes = Files.readAllBytes(path);
        appendOneFile(sb, relativePath, bytes, counter);
    }

    private void appendZip(StringBuilder sb, Path zipPath, Counter counter) throws IOException {
        byte[] zipBytes = Files.readAllBytes(zipPath);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null && counter.count < MAX_INLINE_FILES && counter.totalBytes < MAX_INLINE_TOTAL_BYTES) {
                if (entry.isDirectory()) continue;
                String name = sanitizeRelativePath(entry.getName());
                if (!isSupportedTextFile(name)) {
                    sb.append("\n[Zip-entry пропущен: ").append(zipPath.getFileName()).append("!/").append(name).append(" — неподдерживаемый тип]\n");
                    continue;
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                zis.transferTo(bos);
                appendOneFile(sb, name, bos.toByteArray(), counter);
            }
        }
    }

    private void appendOneFile(StringBuilder sb, String relativePath, byte[] bytes, Counter counter) {
        String safeRelativePath = sanitizeRelativePath(relativePath);
        if (bytes.length > MAX_INLINE_FILE_BYTES) {
            sb.append("\n[Файл пропущен: ").append(safeRelativePath).append(" — больше ").append(MAX_INLINE_FILE_BYTES).append(" байт]\n");
            return;
        }
        if (counter.count >= MAX_INLINE_FILES || counter.totalBytes + bytes.length > MAX_INLINE_TOTAL_BYTES) {
            sb.append("\n[Файл пропущен: ").append(safeRelativePath).append(" — превышен общий лимит вложений]\n");
            return;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        sb.append("\n### FILE: ").append(safeRelativePath).append("\n");
        sb.append("```\n").append(content).append("\n```\n");
        counter.count++;
        counter.totalBytes += bytes.length;
    }

    private boolean isSupportedTextFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".java") || lower.endsWith(".md") || lower.endsWith(".txt")
                || lower.endsWith(".xml") || lower.endsWith(".properties") || lower.endsWith(".json")
                || lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".sql")
                || lower.endsWith(".gradle") || lower.endsWith(".html") || lower.endsWith(".css")
                || lower.endsWith(".js") || lower.endsWith(".ts") || lower.endsWith(".bat")
                || lower.endsWith(".sh") || lower.endsWith(".csv");
    }

    private boolean hasNestedPath(String relativePath) {
        return relativePath.contains("/") || relativePath.contains("\\");
    }

    private String pathForGeneratedFile(String rawPath, String content, String sourceRoot) {
        String sanitized = sanitizeRelativePath(rawPath);
        if (sanitized.toLowerCase(Locale.ROOT).endsWith(".java")) {
            String root = normalizeSourceRoot(sourceRoot);
            Matcher matcher = JAVA_PACKAGE_PATTERN.matcher(content == null ? "" : content);
            if (matcher.find()) {
                String packagePath = matcher.group(1).replace('.', '/');
                return joinPath(root, packagePath + "/" + fileNameOnly(sanitized));
            }
            if (!sanitized.startsWith("src/")) {
                return joinPath(root, fileNameOnly(sanitized));
            }
        }
        return sanitized;
    }

    private String normalizeSourceRoot(String sourceRoot) {
        String root = sanitizeRelativePath(sourceRoot == null ? "" : sourceRoot.trim());
        if (root.equals("generated.txt")) root = "";
        return root;
    }

    private String joinPath(String root, String child) {
        String cleanChild = sanitizeRelativePath(child);
        if (root == null || root.isBlank()) return cleanChild;
        return sanitizeRelativePath(root + "/" + cleanChild);
    }

    private String inferRelativePath(Path path) {
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        int idx = normalized.indexOf("/src/");
        if (idx >= 0) return normalized.substring(idx + 1);
        return path.getFileName().toString();
    }

    private String sanitizeRelativePath(String path) {
        return UserVisibleFile.normalize(path);
    }

    private String fileNameOnly(String path) {
        String normalized = sanitizeRelativePath(path);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private String safeFileName(String value) {
        String safe = value == null || value.isBlank() ? "chat" : value.trim();
        safe = safe.replaceAll("[^\\p{L}\\p{N}._-]+", "_");
        safe = safe.replaceAll("_+", "_");
        return safe.isBlank() ? "chat" : safe;
    }

    private Path unique(Path path) throws IOException {
        if (!Files.exists(path)) return path;
        String fileName = path.getFileName().toString();
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int i = 2; i < 10_000; i++) {
            Path candidate = path.getParent().resolve(base + "-" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        throw new IOException("Не удалось подобрать свободное имя файла: " + path);
    }

    private static class Counter {
        int count;
        long totalBytes;
    }
}
