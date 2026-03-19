package io.transmute.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * Last-resort agent invoked when compile-fix exhausts all iterations.
 * Its only job is to comment out constructs that prevent compilation.
 */
public interface CommentOutBrokenCodeAgent {

    @SystemMessage(fromResource = "comment-out-broken.md")
    @UserMessage("""
        The project at {{outputDir}} still has compilation errors after all fix attempts.
        Errors: {{compileErrors}}

        ## Migration Journal
        {{journalContext}}

        {{converterHints}}

        Comment out every construct that prevents compilation. Mark each with:
          // TRANSMUTE[unsupported]: <brief description>
        Use outputDir-relative paths only.
        """)
    String fix(@V("outputDir") String outputDir,
               @V("compileErrors") String compileErrors,
               @V("journalContext") String journalContext,
               @V("converterHints") String converterHints);
}
