package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Parser;
import xyz.kasperstudios.unai.bridge.script.Command;
import xyz.kasperstudios.unai.bridge.script.Context;
import xyz.kasperstudios.unai.bridge.script.Value;
import java.util.List;
import java.util.regex.Matcher;

public class ReturnCommand implements Command {
    @Override
    public String getName() {
        return "return";
    }

    @Override
    public String getCategory() {
        return "Flow";
    }

    @Override
    public String getRegex() {
        return "^return(?:\\s+(.+))?$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) {
        Matcher m = getMatcher(line);
        if (m.find()) {
            String expr = m.group(1);
            if (expr != null && !expr.trim().isEmpty()) {
                ctx.returnValue = Parser.evaluate(expr, ctx);
            } else {
                ctx.returnValue = Value.NULL;
            }
            ctx.shouldReturn = true;
        }
    }
}
