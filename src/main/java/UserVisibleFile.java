import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class UserVisibleFile implements Serializable
{
    @Serial
    private static final long serialVersionUID = 3L;

    public enum SourceKind
    {LOCAL, UPLOADED_TO_MODEL, GENERATED_BY_AI}

    private final String displayName;
    private final String relativePath;
    private final String localPathString;
    private final byte[] content;
    private final SourceKind sourceKind;

    public UserVisibleFile(String displayName, String relativePath, Path localPath, byte[] content, SourceKind sourceKind)
    {
        this.displayName = displayName == null || displayName.isBlank() ? fileNameFrom(relativePath) : displayName;
        this.relativePath = normalize(relativePath == null || relativePath.isBlank() ? this.displayName : relativePath);
        this.localPathString = localPath == null ? null : localPath.toAbsolutePath().normalize().toString();
        this.content = content == null ? null : Arrays.copyOf(content, content.length);
        this.sourceKind = sourceKind;
    }

    public String displayName()
    {
        return displayName;
    }

    public String relativePath()
    {
        return relativePath;
    }

    public Path localPath()
    {
        return localPathString == null || localPathString.isBlank() ? null : Path.of(localPathString);
    }

    public SourceKind sourceKind()
    {
        return sourceKind;
    }

    public byte[] content()
    {
        return content == null ? null : Arrays.copyOf(content, content.length);
    }

    public long sizeBytes()
    {
        if (content != null) return content.length;
        Path path = localPath();
        if (path != null && Files.isRegularFile(path))
        {
            try
            {
                return Files.size(path);
            }
            catch (IOException ignored)
            {
                return -1L;
            }
        }
        return -1L;
    }

    public String sizeLabel()
    {
        long bytes = sizeBytes();
        if (bytes < 0) return "? КБ";
        long kb = Math.max(1L, Math.round(bytes / 1024.0));
        return kb + " КБ";
    }

    public String displayPathFromSrc()
    {
        String normalized = normalize(relativePath);
        int idx = normalized.indexOf("src/");
        if (idx >= 0) return normalized.substring(idx);
        return normalized;
    }

    public String fileNameOnly()
    {
        return fileNameFrom(relativePath);
    }

    public UserVisibleFile asUploaded()
    {
        return new UserVisibleFile(displayName, relativePath, localPath(), content, SourceKind.UPLOADED_TO_MODEL);
    }

    @Override
    public String toString()
    {
        return displayPathFromSrc() + " — " + sizeLabel();
    }

    public static String normalize(String value)
    {
        if (value == null) return "";
        String normalized = value.replace('\\', '/').replaceAll("^/+", "");
        while (normalized.contains("../"))
        {
            normalized = normalized.replace("../", "");
        }
        return normalized.isBlank() ? "generated.txt" : normalized;
    }

    private static String fileNameFrom(String value)
    {
        String normalized = normalize(value == null || value.isBlank() ? "file" : value);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
