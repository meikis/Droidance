// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.program;

import org.mozilla.javascript.*;
import java.util.function.BooleanSupplier;

/** No LiveConnect, host objects, process APIs, or model calls. The Android worker is also an isolated UID. */
public final class ProgramEngine {
    public interface Host { String invoke(String operation, String arguments) throws Exception; }
    public static final class Interrupted extends Error {
        public Interrupted() { super("PROGRAM_INTERRUPTED"); }
    }
    public static String formatError(Throwable error) {
        if (error instanceof RhinoException) {
            RhinoException rx = (RhinoException) error;
            String lineSource = rx.lineSource();
            StringBuilder sb = new StringBuilder();
            sb.append(rx.sourceName()).append(":").append(rx.lineNumber());
            if (rx.columnNumber() > 0) {
                sb.append(":").append(rx.columnNumber());
            }
            sb.append(": ").append(rx.details());
            if (lineSource != null && !lineSource.trim().isEmpty()) {
                sb.append("\n> ").append(lineSource.trim());
            }
            return sb.toString();
        }
        return error.getClass().getSimpleName() + ": " + error.getMessage();
    }
    private static ContextFactory contextFactory(BooleanSupplier stopped) {
        return new ContextFactory() {
            @Override protected Context makeContext() {
                Context cx = super.makeContext();
                cx.setOptimizationLevel(-1);
                cx.setLanguageVersion(Context.VERSION_ES6);
                cx.setInstructionObserverThreshold(2000);
                cx.setClassShutter(name -> false);
                return cx;
            }
            @Override protected void observeInstructionCount(Context cx, int count) {
                if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new Interrupted();
            }
        };
    }
    /** Grammar check only: never creates a host bridge, initializes globals, or executes code. */
    public void validateSyntax(String code, String sourceName) {
        contextFactory(() -> false).call(cx -> {
            cx.compileString(code, sourceName, 1, null);
            return null;
        });
    }
    public void execute(String code, Host host, BooleanSupplier stopped) {
        contextFactory(stopped).call(cx -> {
            Script compiledScript = cx.compileString(code, "program.js", 1, null);
            ScriptableObject scope = cx.initSafeStandardObjects();
            BaseFunction bridge = new BaseFunction() {
                @Override public Object call(Context context, Scriptable callScope, Scriptable self, Object[] args) {
                    if (stopped.getAsBoolean()) throw new Interrupted();
                    if (args.length != 2) throw Context.reportRuntimeError("host expects operation and JSON");
                    String op = Context.toString(args[0]), json = Context.toString(args[1]);
                    if (op.length() > 64 || json.length() > 128 * 1024) throw Context.reportRuntimeError("request too large");
                    try { return host.invoke(op, json); }
                    catch (Exception error) { throw Context.reportRuntimeError("HOST_CALL_FAILED: " + error.getClass().getSimpleName()); }
                }
            };
            bridge.setParentScope(scope);
            bridge.setPrototype(ScriptableObject.getFunctionPrototype(scope));
            ScriptableObject.defineProperty(scope, "__host", bridge, ScriptableObject.READONLY | ScriptableObject.PERMANENT);
            cx.evaluateString(scope,
                "var _origParse=JSON.parse;\n" +
                "JSON.parse=function(t,r){if(typeof t==='object'&&t!==null)return t;if(typeof t==='string'){var s=t.trim();if(s.indexOf('[ANDROID_CONTENT_RESULT]')===0)s=s.substring(24).trim();return _origParse(s,r);}return _origParse(t,r);};\n" +
                "function call(op,args){var r=_origParse(__host(op,JSON.stringify(args)));if(r.error)throw new Error(r.error);return r.value;}\n" +
                "var workspace=Object.freeze({artifact:function(ref,offset){return call('artifact',{ref:ref,offset:offset||0});},read:function(path){return call('read',{path:path});},write:function(path,text){var b=(typeof text==='object'&&text!==null)?JSON.stringify(text):String(text);return call('write',{path:path,text:b});},list:function(){return call('list',{});}});\n" +
                "var android=Object.freeze({call:function(tool,args){var res=call('android',{tool:tool,args:args});if(res&&typeof res==='object'&&res.data!==undefined&&res.data!==null&&typeof res.data==='object'){for(var k in res.data){if(res[k]===undefined)res[k]=res.data[k];}}return res;}});\n" +
                "function print(value){call('stdout',{text:typeof value==='string'?value:JSON.stringify(value)});}\n" +
                "var skill=Object.freeze({read:function(path){return call('skill_file',{path:path});},checkpoint:function(key,operation){return call('skill_checkpoint',{key:key,operation:operation||'read'});}});\n" +
                "function readLine(){return call('stdin',{});}\n", "environment", 1, null);
            compiledScript.exec(cx, scope);
            return null;
        });
    }
}
