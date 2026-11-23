package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Command;
import xyz.kasperstudios.unai.bridge.script.Context;
import xyz.kasperstudios.unai.bridge.script.Value;
import xyz.kasperstudios.unai.bridge.script.Parser;

import java.util.List;
import java.util.regex.Matcher;

/**
 * AssignmentCommand - Handles variable assignment.
 * Usage: x = x + 1
 */
public class AssignmentCommand implements Command {
    @Override
    public String getName() {
        return "assignment";
    }

    @Override
    public String getCategory() {
        return "Core";
    }

    // Matches: x = 10, but NOT starting with let/var
    @Override
    public String getRegex() {
        return "^(?!(?:let|var)\\s+)([a-zA-Z0-9_.]+)\\s*=\\s*(.+)$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) throws Exception {
        Matcher m = getMatcher(line);
        if (m.find()) {
            String fullName = m.group(1);
            String expr = m.group(2);
            Value value = Parser.evaluate(expr, ctx);

            if (fullName.contains(".")) {
                String[] parts = fullName.split("\\.");
                Value target = ctx.getVariable(parts[0]);
                for (int i = 1; i < parts.length - 1; i++) {
                    target = target.getMember(parts[i]);
                }
                if (!target.isNull()) {
                    target.setMember(parts[parts.length - 1], value);
                }
            } else {
                ctx.setVariable(fullName, value);
            }
        }
    }
}
