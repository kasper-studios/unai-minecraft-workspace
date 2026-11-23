package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Command;
import xyz.kasperstudios.unai.bridge.script.Context;
import xyz.kasperstudios.unai.bridge.script.Interpreter;

import java.util.List;

/**
 * ElseCommand - Handles 'else' blocks.
 * Usage: else { ... }
 */
public class ElseCommand implements Command {
    @Override
    public String getName() {
        return "else";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public String getRegex() {
        return "^(?:\\}\\s*)?else\\s*\\{\\s*$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) throws Exception {
        if (!ctx.lastIfConditionResult && blockBody != null) {
            Context innerCtx = new Context(ctx);
            Interpreter.executeBlock(blockBody, innerCtx);

            // Propagate flow control flags
            ctx.shouldBreak = innerCtx.shouldBreak;
            ctx.shouldContinue = innerCtx.shouldContinue;
            ctx.shouldReturn = innerCtx.shouldReturn;
            ctx.returnValue = innerCtx.returnValue;
        }
    }
}
