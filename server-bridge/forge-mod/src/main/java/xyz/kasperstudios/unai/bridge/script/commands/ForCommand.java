package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Parser;
import xyz.kasperstudios.unai.bridge.script.Context;
import xyz.kasperstudios.unai.bridge.script.Interpreter;
import xyz.kasperstudios.unai.bridge.script.Command;
import xyz.kasperstudios.unai.bridge.script.Value;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * ForCommand - Handles JS-like 'for' loops.
 * Usage: for (let i = 0; i < 10; i = i + 1) { ... }
 */
public class ForCommand implements Command {
    @Override
    public String getName() {
        return "for";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public String getRegex() {
        return "^for\\s*\\((.+);\\s*(.+);\\s*(.+)\\)\\s*\\{$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) throws Exception {
        Matcher m = getMatcher(line);
        if (m.find()) {
            String init = m.group(1).trim();
            String condition = m.group(2).trim();
            String update = m.group(3).trim();

            Context loopCtx = new Context(ctx);

            if (!init.isEmpty()) {
                Command declCmd = xyz.kasperstudios.unai.bridge.script.Registry.findMatch(init);
                if (declCmd != null) {
                    declCmd.execute(loopCtx, init, null);
                } else {
                    Parser.evaluate(init, loopCtx);
                }
            }

            while (!ctx.shouldStop && !Thread.currentThread().isInterrupted()
                    && Parser.evaluate(condition, loopCtx).asBoolean()) {
                Context innerCtx = new Context(loopCtx);
                Interpreter.executeBlock(blockBody, innerCtx);

                if (innerCtx.shouldBreak)
                    break;
                if (innerCtx.shouldContinue) {
                    // continue -> run update, then next iteration
                }
                if (innerCtx.shouldReturn) {
                    ctx.shouldReturn = true;
                    ctx.returnValue = innerCtx.returnValue;
                    break;
                }

                if (!update.isEmpty()) {
                    Command updCmd = xyz.kasperstudios.unai.bridge.script.Registry.findMatch(update);
                    if (updCmd != null) {
                        updCmd.execute(loopCtx, update, null);
                    } else {
                        Parser.evaluate(update, loopCtx);
                    }
                }
            }
        }
    }
}
