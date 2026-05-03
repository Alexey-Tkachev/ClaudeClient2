import java.util.List;

public record FileProcessingResult(String visibleAnswer, List<UserVisibleFile> generatedFiles)
{
}
