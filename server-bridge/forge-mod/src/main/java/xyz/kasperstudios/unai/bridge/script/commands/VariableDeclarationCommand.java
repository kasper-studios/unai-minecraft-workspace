package xyz.kasperstudios.unai.bridge.script.commands;

import xyz.kasperstudios.unai.bridge.script.Parser;
import xyz.kasperstudios.unai.bridge.script.Command;
import xyz.kasperstudios.unai.bridge.script.Context;
import xyz.kasperstudios.unai.bridge.script.Value;

import java.util.List;
import java.util.regex.Matcher;

/**
 * VariableDeclarationCommand - Handles 'let' and 'var' declarations.
 * Usage: let x = 10 + 5
 */
public class VariableDeclarationCommand implements Command {
    @Override
    public String getName() {
        return "var";
    }

    @Override
    public String getCategory() {
        return "Core";
    }

    @Override
    public String getRegex() {
        return "^(let|var)\\s+([a-zA-Z0-9_]+)(?:\\s*=\\s*(.+))?$";
    }

    @Override
    public void execute(Context ctx, String line, List<String> blockBody) throws Exception {
        Matcher m = getMatcher(line);
        if (m.find()) {
            String name = m.group(2);
            String expr = m.group(3);
            Value value = expr != null ? Parser.evaluate(expr, ctx) : Value.NULL;
            ctx.declareVariable(name, value);
        }
    }
}
