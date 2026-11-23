package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Parser;
import xyz.kasperstudios.unai.bridge.script.Context;
import xyz.kasperstudios.unai.bridge.script.Interpreter;
import xyz.kasperstudios.unai.bridge.script.Command;

import java.util.List;
import java.util.regex.Matcher;

/**
 * IfCommand - Handles JS-like 'if' blocks.
 * Usage: if (condition) { ... }
 */
public class IfCommand implements Command {
    @Override
    public String getName() {
        return "if";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public String getRegex() {
        return "^(?:\\}\\s*)?(?:else\\s+)?if\\s*\\((.+)\\)\\s*\\{\\s*$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) throws Exception {
        Matcher m = getMatcher(line);
        if (m.find()) {
            boolean isElseIf = line.trim().startsWith("}") || line.trim().startsWith("else");
            
            // If this is an 'else if' and a previous 'if' in the chain already matched, skip this one
            if (isElseIf && ctx.lastIfConditionResult) {
                // Keep lastIfConditionResult as true so following 'else' blocks are also skipped
                return;
            }

            String condition = m.group(1);
            boolean result = Parser.evaluate(condition, ctx).asBoolean();
            ctx.lastIfConditionResult = result;

            if (result && blockBody != null) {
                // Create a new nested scope for the block
                Context innerCtx = new Context(ctx);
                Interpreter.executeBlock(blockBody, innerCtx);

                // Propagate flow control flags to parent
                ctx.shouldBreak = innerCtx.shouldBreak;
                ctx.shouldContinue = innerCtx.shouldContinue;
                ctx.shouldReturn = innerCtx.shouldReturn;
                ctx.returnValue = innerCtx.returnValue;
            }
        }
    }
}
