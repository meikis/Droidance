// SPDX-FileCopyrightText: 2026 YBBLab
// SPDX-License-Identifier: Apache-2.0

package com.droidance.core.device.program;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ProgramEngineTest {
    @Test public void programComputesAndUsesOnlyJsonCapabilities() {
        List<String> requests = new ArrayList<>();
        new ProgramEngine().execute("var sum=0; for(var i=0;i<5;i++)sum+=i; workspace.write('sum.txt',String(sum)); print(sum);",
            (op, json) -> { requests.add(op + ":" + json); return "{\"value\":null}"; }, () -> false);
        assertEquals("write:{\"path\":\"sum.txt\",\"text\":\"10\"}", requests.get(0));
        assertEquals("stdout:{\"text\":\"10\"}", requests.get(1));
    }
    @Test public void noJavaReflectionOrOsObjectsAreExposed() {
        new ProgramEngine().execute("if(typeof java!=='undefined'||typeof Packages!=='undefined'||typeof process!=='undefined')throw Error('host leak');" +
            "if(typeof __host.getClass!=='undefined')throw Error('reflection');", (op, json) -> { fail("no call expected"); return "{}"; }, () -> false);
    }
    @Test public void cancellationEscapesScriptCatchAndFinally() {
        AtomicInteger checks = new AtomicInteger();
        try {
            new ProgramEngine().execute("try{while(true){}}catch(e){while(true){}}finally{while(true){}}", (op,json) -> "{}", () -> checks.incrementAndGet()>2);
            fail("infinite script must stop");
        } catch (ProgramEngine.Interrupted expected) { assertTrue(checks.get()>2); }
    }
    @Test public void runtimeErrorDoesNotPoisonNextRun() {
        ProgramEngine engine = new ProgramEngine();
        try { engine.execute("missingFunction()", (op,json)->"{}", ()->false); fail(); }
        catch (org.mozilla.javascript.RhinoException expected) { }
        engine.execute("var value=2+3; if(value!==5)throw Error('bad');", (op,json)->"{}", ()->false);
    }
    @Test public void hostFailureIsVisibleToScript() {
        List<String> output = new ArrayList<>();
        new ProgramEngine().execute("try{workspace.read('missing')}catch(e){print('repair-needed')}", (op,json)-> {
            if(op.equals("read"))return "{\"error\":\"FILE_NOT_FOUND\"}";
            output.add(json); return "{\"value\":null}";
        }, ()->false);
        assertEquals(1,output.size());
        assertTrue(output.get(0).contains("repair-needed"));
    }
    @Test public void skillReadUsesDedicatedReadOnlyHostCapability() {
        List<String> requests = new ArrayList<>();
        new ProgramEngine().execute(
            "if(typeof skill.write!=='undefined'||typeof skill.list!=='undefined')throw Error('skill must be read-only');" +
            "var content=skill.read('references/example.md'); if(content!=='body')throw Error('wrong skill content'); print(content);",
            (op, json) -> {
                requests.add(op + ":" + json);
                if (op.equals("skill_file")) {
                    assertEquals("{\"path\":\"references/example.md\"}", json);
                    return "{\"value\":\"body\"}";
                }
                assertEquals("stdout", op);
                assertEquals("{\"text\":\"body\"}", json);
                return "{\"value\":null}";
            }, () -> false);
        assertEquals(2, requests.size());
    }
    @Test public void skillNamespaceCannotBeReplacedByProgram() {
        List<String> requests = new ArrayList<>();
        new ProgramEngine().execute(
            "skill.read=function(){return 'forged';};" +
            "if(skill.read('references/example.md')!=='body')throw Error('skill namespace mutated');",
            (op, json) -> {
                requests.add(op + ":" + json);
                return "{\"value\":\"body\"}";
            }, () -> false);
        assertEquals(1, requests.size());
        assertTrue(requests.get(0).startsWith("skill_file:"));
    }
    @Test public void skillCheckpointUsesDedicatedHostOperationAndJsonArguments() {
        List<String> requests = new ArrayList<>();
        new ProgramEngine().execute(
            "var result=skill.checkpoint('item-1','begin'); if(result.execute!==true)throw Error('checkpoint not executed');",
            (op, json) -> {
                requests.add(op + ":" + json);
                assertEquals("skill_checkpoint", op);
                assertEquals("{\"key\":\"item-1\",\"operation\":\"begin\"}", json);
                return "{\"value\":{\"state\":\"pending\",\"execute\":true}}";
            }, () -> false);
        assertEquals(1, requests.size());
    }
    @Test public void syntaxErrorFailsPreParseWithoutHostSideEffects() {
        List<String> hostCalls = new ArrayList<>();
        try {
            new ProgramEngine().execute("var x = ;\nprint('unreachable');", (op, json) -> {
                hostCalls.add(op);
                return "{\"value\":null}";
            }, () -> false);
            fail("Syntax error must fail before execution");
        } catch (org.mozilla.javascript.RhinoException ex) {
            assertTrue(hostCalls.isEmpty());
            String formatted = ProgramEngine.formatError(ex);
            assertTrue(formatted.contains("program.js:1"));
            assertTrue(formatted.contains("var x = ;"));
        }
    }
    @Test(timeout = 2000) public void syntaxValidationDoesNotExecuteCodeOrRequireHostGlobals() {
        new ProgramEngine().validateSyntax(
            "throw new Error('must not run'); while(true){} android.call('screen_act',{}); workspace.write('file','value');",
            "scripts/workflow.js");
    }
    @Test public void syntaxValidationUsesTheExecutionGrammarAndRetainsPackageLocations() {
        ProgramEngine engine = new ProgramEngine();
        String code = "const values=[1,2,3]; let total=0; values.forEach(value=>total+=value); print(total);";
        engine.validateSyntax(code, "scripts/es6.js");
        List<String> output = new ArrayList<>();
        engine.execute(code, (op, json) -> { output.add(op + ":" + json); return "{\"value\":null}"; }, () -> false);
        assertEquals("stdout:{\"text\":\"6\"}", output.get(0));
        try {
            engine.validateSyntax("var valid=1;\nvar broken=;", "scripts/nested/workflow.js");
            fail("Invalid source must not pass validation");
        } catch (org.mozilla.javascript.RhinoException error) {
            String formatted = ProgramEngine.formatError(error);
            assertTrue(formatted.contains("scripts/nested/workflow.js:2"));
            assertTrue(formatted.contains("var broken=;"));
        }
    }
}
