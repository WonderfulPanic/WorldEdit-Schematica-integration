/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.scripting;

import com.google.common.io.CharStreams;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.scripting.compat.BabelScriptTranspiler;
import com.sk89q.worldedit.scripting.compat.ScriptTranspiler;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.WrappedException;

import javax.script.ScriptException;
import java.io.StringReader;
import java.util.Map;

public class RhinoCraftScriptEngine implements CraftScriptEngine {

    private static final ScriptTranspiler TRANSPILER = new BabelScriptTranspiler();
    private int timeLimit;

    @Override
    public void setTimeLimit(int milliseconds) {
        timeLimit = milliseconds;
    }

    @Override
    public int getTimeLimit() {
        return timeLimit;
    }

    @Override
    public Object evaluate(String script, String filename, Map<String, Object> args)
            throws ScriptException, Throwable {
        String transpiled = CharStreams.toString(TRANSPILER.transpile(new StringReader(script)));
        RhinoContextFactory factory = new RhinoContextFactory(timeLimit);
        Context cx = factory.enterContext();
        cx.setClassShutter(new MinecraftHidingClassShutter());
        ScriptableObject scriptable = new ImporterTopLevel(cx);
        Scriptable scope = cx.initStandardObjects(scriptable);

        for (Map.Entry<String, Object> entry : args.entrySet()) {
            ScriptableObject.putProperty(scope, entry.getKey(),
                    Context.javaToJS(entry.getValue(), scope));
        }
        try {
            return cx.evaluateString(scope, transpiled, filename, 1, null);
        } catch (Error e) {
            throw new ScriptException(e.getMessage());
        } catch (RhinoException e) {
            if (e instanceof WrappedException) {
                Throwable cause = e.getCause();
                if (cause instanceof WorldEditException) {
                    throw cause;
                }
            }

            String msg;
            int line = (line = e.lineNumber()) == 0 ? -1 : line;

            if (e instanceof JavaScriptException) {
                msg = String.valueOf(((JavaScriptException) e).getValue());
            } else {
                msg = e.getMessage();
            }

            ScriptException scriptException =
                    new ScriptException(msg, e.sourceName(), line);
            scriptException.initCause(e);

            throw scriptException;
        } finally {
            Context.exit();
        }
    }

}
