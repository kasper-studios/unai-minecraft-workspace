package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Command;
import xyz.kasperstudios.unai.bridge.script.Context;
import java.util.List;

public class ContinueCommand implements Command {
    @Override
    public String getName() {
        return "continue";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public String getRegex() {
        return "^continue$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) {
        ctx.shouldContinue = true;
    }
}
