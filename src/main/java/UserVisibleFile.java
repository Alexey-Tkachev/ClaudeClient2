import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Path;
import java.util.Arrays;

public class UserVisibleFile implements Serializable
{
    @Serial
    private static final long serialVersionUID = 1L;

    public enum SourceKind
    {LOCAL, UPLOADED_TO_MODEL, GENERATED_BY_AI}

    private final String displayName;
    private final String relativePath;
    private final Path localPath;
    private final byte[] content;
    private final SourceKind sourceKind;

    public UserVisibleFile(String displayName, String relativePath, Path localPath, byte[] content, SourceKind sourceKind)
    {
        this.displayName = displayName;
        this.relativePath = normalize(relativePath == null || relativePath.isBlank() ? displayName : relativePath);
        this.localPath = localPath;
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
        return localPath;
    }

    public SourceKind sourceKind()
    {
        return sourceKind;
    }

    public byte[] content()
    {
        return content == null ? null : Arrays.copyOf(content, content.length);
    }

    public UserVisibleFile asUploaded()
    {
        return new UserVisibleFile(displayName, relativePath, localPath, content, SourceKind.UPLOADED_TO_MODEL);
    }

    @Override
    public String toString()
    {
        if (sourceKind == SourceKind.LOCAL && localPath != null) return localPath.toString();
        return relativePath;
    }

    private static String normalize(String value)
    {
        return value.replace('\\', '/').replaceAll("^/+", "");
    }
}
