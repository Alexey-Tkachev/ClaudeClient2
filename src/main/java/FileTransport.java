import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface FileTransport {
    List<UserVisibleFile> prepareForModel(List<Path> localFiles) throws IOException;

    String buildPromptAppendix(List<UserVisibleFile> uploadedFiles) throws IOException;

    FileProcessingResult extractGeneratedFiles(String modelAnswer) throws IOException;

    Path saveGeneratedFiles(List<UserVisibleFile> files, Path downloadsDir, String modelDisplayName, String chatName, String sourceRoot) throws IOException;
}
