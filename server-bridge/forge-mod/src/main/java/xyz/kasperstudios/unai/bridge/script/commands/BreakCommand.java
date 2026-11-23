package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Command;
import xyz.kasperstudios.unai.bridge.script.Context;
import java.util.List;

public class BreakCommand implements Command {
    @Override
    public String getName() {
        return "break";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public String getRegex() {
        return "^break$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) {
        ctx.shouldBreak = true;
    }
}
