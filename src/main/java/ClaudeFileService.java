import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper for Claude/ProxyAPI Files API and code execution.
 * Uses only JDK 17 HttpClient; the normal chat path may still use Anthropic Java SDK.
 */
public final class ClaudeFileService
{
    public static final String FILES_BETA_HEADER = "files-api-2025-04-14";
    public static final String CODE_EXECUTION_TOOL = "code_execution_20250825";
    public static final String DEFAULT_PROXY_BASE_URL = "https://api.proxyapi.ru/anthropic";
    public static final String DEFAULT_PROXY_ROOT_URL = "https://api.proxyapi.ru";

    private static final Pattern FILE_ID_PATTERN = Pattern.compile("\\\"(?:file_id|id)\\\"\\s*:\\s*\\\"(file_[^\\\"]+)\\\"");
    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"(file_[^\\\"]+)\\\"");

    private final HttpClient httpClient;
    private final String apiKey;
    private final String anthropicBaseUrl;

    public ClaudeFileService(String apiKey, String anthropicBaseUrl)
    {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.anthropicBaseUrl = trimTrailingSlash(anthropicBaseUrl == null || anthropicBaseUrl.isBlank()
                ? DEFAULT_PROXY_BASE_URL
                : anthropicBaseUrl.trim());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    public String uploadFile(Path file) throws IOException, InterruptedException
    {
        requireApiKey();
        if (file == null || !Files.isRegularFile(file))
        {
            throw new IOException("Not a regular file: " + file);
        }

        String mimeType = Files.probeContentType(file);
        if (mimeType == null || mimeType.isBlank())
        {
            mimeType = guessMimeType(file);
        }

        String boundary = "----ClaudeClientBoundary" + UUID.randomUUID();
        HttpRequest.BodyPublisher bodyPublisher = multipartFileBody("file", file, mimeType, boundary);

        HttpRequest request = anthropicRequest("/v1/files")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .timeout(Duration.ofMinutes(5))
                .POST(bodyPublisher)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSuccessful(response.statusCode()))
        {
            throw new IOException("Files API upload failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    public String listFiles() throws IOException, InterruptedException
    {
        requireApiKey();
        HttpRequest request = anthropicRequest("/v1/files")
                .timeout(Duration.ofMinutes(2))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSuccessful(response.statusCode()))
        {
            throw new IOException("Files API list failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    /**
     * Download is allowed only for files created by Claude skills/code execution.
     * Uploaded user files are usually not downloadable.
     */
    public void downloadGeneratedFile(String fileId, Path saveTo) throws IOException, InterruptedException
    {
        requireApiKey();
        if (fileId == null || fileId.isBlank())
        {
            throw new IOException("fileId is empty");
        }
        if (saveTo == null)
        {
            throw new IOException("saveTo is null");
        }

        HttpRequest request = anthropicRequest("/v1/files/" + urlPart(fileId) + "/content")
                .timeout(Duration.ofMinutes(5))
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (!isSuccessful(response.statusCode()))
        {
            throw new IOException("Files API download failed: HTTP " + response.statusCode() + " "
                    + new String(response.body(), StandardCharsets.UTF_8));
        }

        Path parent = saveTo.toAbsolutePath().getParent();
        if (parent != null)
        {
            Files.createDirectories(parent);
        }
        Files.write(saveTo, response.body());
    }

    /**
     * Raw Messages request with server-side code execution.
     * This is separate from the ordinary SDK chat path because beta tool support changes faster than the SDK surface.
     */
    public String createMessageWithCodeExecution(String prompt, List<String> uploadedFileIds, long maxTokens) throws IOException, InterruptedException
    {
        requireApiKey();
        String text = prompt == null || prompt.isBlank()
                ? "Создай файл result.txt с текстом OK и сделай его доступным для скачивания через Files API."
                : prompt.trim();

        StringBuilder content = new StringBuilder();
        content.append("[{\"type\":\"text\",\"text\":")
                .append(jsonString(text + "\n\nСоздай реальные файлы в песочнице code_execution. Если создаёшь несколько файлов, упакуй их в один ZIP. Сделай результат доступным для скачивания через Files API и явно перечисли file_id в ответе."))
                .append("}");
        if (uploadedFileIds != null)
        {
            for (String id : uploadedFileIds)
            {
                if (id != null && !id.isBlank())
                {
                    content.append(",{\"type\":\"container_upload\",\"file_id\":")
                            .append(jsonString(id.trim()))
                            .append("}");
                }
            }
        }
        content.append("]");

        String body = "{"
                + "\"model\":\"claude-opus-4-7\","
                + "\"max_tokens\":" + Math.max(1L, maxTokens) + ","
                + "\"tools\":[{\"type\":\"" + CODE_EXECUTION_TOOL + "\",\"name\":\"code_execution\"}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":" + content + "}]"
                + "}";

        HttpRequest request = anthropicRequest("/v1/messages")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSuccessful(response.statusCode()))
        {
            throw new IOException("Code execution request failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    public String readProxyBalance() throws IOException, InterruptedException
    {
        requireApiKey();
        URI uri = URI.create(DEFAULT_PROXY_ROOT_URL + "/proxyapi/balance");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(1))
                .header("Authorization", "Bearer " + apiKey)
                .header("x-api-key", apiKey)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSuccessful(response.statusCode()))
        {
            throw new IOException("Balance request failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    public static String extractUploadedFileId(String json)
    {
        if (json == null) return "";
        Matcher matcher = UPLOAD_ID_PATTERN.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    public static List<String> extractFileIds(String json)
    {
        Set<String> ids = new LinkedHashSet<>();
        if (json != null)
        {
            Matcher matcher = FILE_ID_PATTERN.matcher(json);
            while (matcher.find())
            {
                ids.add(matcher.group(1));
            }
        }
        return new ArrayList<>(ids);
    }

    private HttpRequest.Builder anthropicRequest(String path)
    {
        return HttpRequest.newBuilder(URI.create(anthropicBaseUrl + path))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", FILES_BETA_HEADER);
    }

    private void requireApiKey() throws IOException
    {
        if (apiKey.isBlank())
        {
            throw new IOException("API key is empty");
        }
    }

    private static HttpRequest.BodyPublisher multipartFileBody(
            String fieldName,
            Path file,
            String mimeType,
            String boundary
    ) throws IOException
    {
        String fileName = file.getFileName().toString();
        List<byte[]> parts = new ArrayList<>();
        parts.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + escapeQuotes(fileName) + "\"\r\n"
                + "Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(Files.readAllBytes(file));
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArrays(parts);
    }

    private static boolean isSuccessful(int statusCode)
    {
        return statusCode >= 200 && statusCode < 300;
    }

    private static String trimTrailingSlash(String value)
    {
        while (value.endsWith("/"))
        {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String escapeQuotes(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String urlPart(String value)
    {
        return value.replace("/", "").replace("..", "");
    }

    private static String jsonString(String value)
    {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            switch (c)
            {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default ->
                {
                    if (c < 0x20)
                    {
                        sb.append(String.format("\\u%04x", (int) c));
                    }
                    else
                    {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static String guessMimeType(Path file)
    {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".java")) return "text/x-java-source";
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".txt")) return "text/plain";
        if (name.endsWith(".zip")) return "application/zip";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".pdf")) return "application/pdf";
        return "application/octet-stream";
    }
}
