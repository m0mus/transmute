package io.transmute.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import io.transmute.validation.SyntaxValidator;

/**
 * Validates transformed Java source code for syntax correctness.
 */
public class ValidationTool {

    private final SyntaxValidator syntaxValidator = new SyntaxValidator();

    @Tool("Parse Java source code and check if it is syntactically valid. " +
          "Returns 'valid' or 'invalid' with error details.")
    public String validateSyntax(
            @P("The Java source code to validate") String source) {
        ToolLog.log("validate_syntax (" + source.length() + " chars)");
        var result = syntaxValidator.validate(source);
        if (result.valid()) {
            return "valid";
        }
        return "invalid -- errors:\n" + String.join("\n", result.errors());
    }
}
