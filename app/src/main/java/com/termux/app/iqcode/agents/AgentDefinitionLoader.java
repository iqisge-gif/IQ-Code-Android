package com.termux.app.iqcode.agents;

import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads IQ Code compatible agent markdown files with YAML frontmatter. */
public final class AgentDefinitionLoader {
    private AgentDefinitionLoader() {}

    public static List<AgentDefinition> loadAll(String projectDirectory) {
        LinkedHashMap<String,AgentDefinition> out = new LinkedHashMap<>();
        // Lowest priority first so later scopes replace earlier definitions.
        put(out, builtInGeneral());
        put(out, builtInExplore());
        put(out, builtInPlan());
        put(out, builtInVerification());
        put(out, builtInGuide());
        loadDir(out, new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".iq/agents"), "user");
        File project = projectDirectory == null ? null : new File(projectDirectory);
        // IQ Code discovers project agents by walking from cwd upward. Load parents first,
        // then the closest project directory last so the closest definition wins.
        List<File> chain = new ArrayList<>();
        for (File p=project; p!=null; p=p.getParentFile()) chain.add(p);
        for (int i=chain.size()-1;i>=0;i--) loadDir(out, new File(chain.get(i), ".iq/agents"), "project");
        return new ArrayList<>(out.values());
    }

    public static AgentDefinition resolve(String projectDirectory, String name) {
        String wanted = name == null || name.trim().isEmpty() ? "general-purpose" : name.trim();
        for (AgentDefinition d : loadAll(projectDirectory)) if (d.name.equalsIgnoreCase(wanted)) return d;
        return null;
    }

    private static void put(Map<String,AgentDefinition> out, AgentDefinition d) {
        if (d != null && !d.name.isEmpty()) out.put(d.name.toLowerCase(Locale.US), d);
    }

    private static void loadDir(Map<String,AgentDefinition> out, File dir, String source) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles((d,n) -> n.toLowerCase(Locale.US).endsWith(".md"));
        if (files == null) return;
        Arrays.sort(files, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : files) {
            try { AgentDefinition d = parse(f, source); if (d != null) put(out,d); } catch (Exception ignored) { }
        }
    }

    public static AgentDefinition parse(File file, String source) throws Exception {
        String text = read(file);
        AgentDefinition d = new AgentDefinition();
        d.source = source == null ? "custom" : source;
        d.sourceFile = file;
        d.name = stripExt(file.getName());
        if (!text.startsWith("---")) { d.prompt=text.trim(); return d; }
        int end = text.indexOf("\n---", 3);
        if (end < 0) { d.prompt=text.trim(); return d; }
        String yaml = text.substring(text.indexOf('\n')+1, end);
        d.prompt = text.substring(end + 4).trim();
        parseFrontmatter(yaml,d);
        if (d.name.trim().isEmpty()) d.name=stripExt(file.getName());
        return d;
    }

    private static void parseFrontmatter(String yaml, AgentDefinition d) {
        String activeListKey = null;
        for (String raw : yaml.split("\\r?\\n")) {
            String line=raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("-") && activeListKey != null) {
                addListValue(d,activeListKey,unquote(line.substring(1).trim())); continue;
            }
            int colon=line.indexOf(':'); if (colon<=0) continue;
            String key=line.substring(0,colon).trim(); String val=line.substring(colon+1).trim();
            activeListKey = val.isEmpty() && isListKey(key) ? key : null;
            if (activeListKey != null) continue;
            switch (key) {
                case "name": d.name=unquote(val); break;
                case "description": d.description=unquote(val); break;
                case "model": d.model=unquote(val); break;
                case "effort": d.effort=unquote(val); break;
                case "permissionMode": d.permissionMode=unquote(val); break;
                case "isolation": d.isolation=unquote(val); break;
                case "color": d.color=unquote(val); break;
                case "memory": d.memory=unquote(val); break;
                case "background": d.background=parseBool(val); break;
                case "maxTurns": try { d.maxTurns=Integer.parseInt(unquote(val)); } catch(Exception ignored){} break;
                case "tools": addList(d.tools,val); break;
                case "disallowedTools": addList(d.disallowedTools,val); break;
                case "skills": addList(d.skills,val); break;
                default: break;
            }
        }
    }

    private static boolean isListKey(String k) { return "tools".equals(k)||"disallowedTools".equals(k)||"skills".equals(k); }
    private static void addListValue(AgentDefinition d,String key,String value) {
        if ("tools".equals(key)) d.tools.add(value); else if ("disallowedTools".equals(key)) d.disallowedTools.add(value); else if ("skills".equals(key)) d.skills.add(value);
    }
    private static void addList(Set<String> out,String raw) {
        String s=unquote(raw.trim());
        if (s.startsWith("[")&&s.endsWith("]")) s=s.substring(1,s.length()-1);
        for(String p:s.split(",")){String v=unquote(p.trim()); if(!v.isEmpty())out.add(v);}
    }
    private static String unquote(String s){if(s==null)return "";String v=s.trim();if(v.length()>=2&&((v.startsWith("\"")&&v.endsWith("\""))||(v.startsWith("'")&&v.endsWith("'"))))return v.substring(1,v.length()-1);return v;}
    private static boolean parseBool(String s){String v=unquote(s);return "true".equalsIgnoreCase(v)||"yes".equalsIgnoreCase(v)||"1".equals(v);}
    private static String stripExt(String n){int p=n.lastIndexOf('.');return p>0?n.substring(0,p):n;}
    private static String read(File f)throws Exception{byte[]b=new byte[(int)f.length()];try(FileInputStream in=new FileInputStream(f)){int o=0,n;while(o<b.length&&(n=in.read(b,o,b.length-o))>0)o+=n;}return new String(b,StandardCharsets.UTF_8);}

    private static AgentDefinition builtInGeneral(){
        AgentDefinition d=base("general-purpose","General-purpose agent for complex research and multi-step coding tasks.","built-in"); d.tools.add("*");
        d.prompt="You are a general-purpose IQ Code subagent. Complete the delegated task fully. Search broadly when necessary, analyze relevant files, make scoped changes when asked, verify meaningful changes, and return a concise report with essential findings and actions. Do not create documentation unless explicitly requested."; return d;
    }
    private static AgentDefinition builtInExplore(){
        AgentDefinition d=base("Explore","Fast read-only agent specialized for searching and understanding codebases.","built-in"); d.model="haiku"; d.permissionMode="plan";
        d.tools.addAll(Arrays.asList("Read","ReadMany","Stat","Tree","Glob","Grep","LS","GitStatus","WebSearch","WebFetch","TaskCreate","TaskGet","TaskList","TaskUpdate"));
        d.prompt="You are the Explore subagent. Work strictly read-only. Search the codebase thoroughly using Read, Glob, Grep, Tree, Stat, LS and GitStatus. Never create, edit, delete, move, copy or install anything. Adapt depth to the requested quick/medium/very thorough level and return a concise evidence-based report."; return d;
    }
    private static AgentDefinition builtInPlan(){
        AgentDefinition d=base("Plan","Read-only software architecture and implementation planning agent.","built-in"); d.permissionMode="plan";
        d.tools.addAll(Arrays.asList("Read","ReadMany","Stat","Tree","Glob","Grep","LS","GitStatus","WebSearch","WebFetch","TaskCreate","TaskGet","TaskList","TaskUpdate"));
        d.prompt="You are the Plan subagent. Work strictly read-only. Understand requirements, explore existing architecture and conventions, identify affected files and risks, then return a concrete implementation plan with verification steps. Do not modify project or system state."; return d;
    }
    private static AgentDefinition builtInVerification(){
        AgentDefinition d=base("verification","Verification-only agent for checking implementation correctness.","built-in"); d.background=true;
        d.tools.addAll(Arrays.asList("Read","ReadMany","Stat","Tree","Glob","Grep","LS","GitStatus","WebSearch","WebFetch","Bash","TaskCreate","TaskGet","TaskList","TaskUpdate"));
        d.prompt="You are a verification-only IQ Code subagent. Inspect changes and run relevant tests/checks. Do not edit project files. End with exactly one of VERDICT: PASS, VERDICT: FAIL, or VERDICT: PARTIAL, followed by concise evidence."; return d;
    }
    private static AgentDefinition builtInGuide(){
        AgentDefinition d=base("iq-code-guide","Helper agent for questions about IQ Code behavior and configuration.","built-in"); d.model="haiku"; d.permissionMode="dontAsk";
        d.tools.addAll(Arrays.asList("Read","ReadMany","Stat","Tree","Glob","Grep","LS","WebSearch","WebFetch"));
        d.prompt="You are a IQ Code guide subagent. Answer questions using available local project and bundled documentation evidence. Prefer exact configuration paths, command names and actionable examples. Do not modify files."; return d;
    }
    private static AgentDefinition base(String name,String description,String source){AgentDefinition d=new AgentDefinition();d.name=name;d.description=description;d.source=source;return d;}
}
