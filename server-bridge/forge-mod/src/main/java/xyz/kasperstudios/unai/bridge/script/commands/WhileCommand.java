package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Parser;
import xyz.kasperstudios.unai.bridge.script.Context;
import xyz.kasperstudios.unai.bridge.script.Interpreter;
import xyz.kasperstudios.unai.bridge.script.Command;

import java.util.List;
import java.util.regex.Matcher;

/**
 * WhileCommand - Handles JS-like 'while' loops.
 * Usage: while (condition) { ... }
 */
public class WhileCommand implements Command {
    @Override
    public String getName() {
        return "while";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public String getRegex() {
        return "^while\\s*\\((.+)\\)\\s*\\{$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) throws Exception {
        Matcher m = getMatcher(line);
        if (m.find()) {
            String condition = m.group(1);

            while (!ctx.shouldStop && !Thread.currentThread().isInterrupted() && Parser.evaluate(condition, ctx).asBoolean()) {
                Context innerCtx = new Context(ctx);
                Interpreter.executeBlock(blockBody, innerCtx);

                // Handle break/continue/return
                if (innerCtx.shouldBreak)
                    break;
                if (innerCtx.shouldContinue) {
                    // Just continue the Java loop
                    continue;
                }
                if (innerCtx.shouldReturn) {
                    ctx.shouldReturn = true;
                    ctx.returnValue = innerCtx.returnValue;
                    break;
                }
            }
        }
    }
}
