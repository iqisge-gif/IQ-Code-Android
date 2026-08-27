package com.termux.app.iqcode.agents;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Parsed IQ Code subagent definition (built-in, project or user scope). */
public final class AgentDefinition {
    public String name = "";
    public String description = "";
    public String prompt = "";
    public String model = "inherit";
    public String effort = "";
    public String permissionMode = "";
    public String isolation = "";
    public String color = "";
    public String memory = "";
    public boolean background;
    public int maxTurns;
    public String source = "custom";
    public File sourceFile;
    public final Set<String> tools = new LinkedHashSet<>();
    public final Set<String> disallowedTools = new LinkedHashSet<>();
    public final Set<String> skills = new LinkedHashSet<>();

    public boolean inheritsAllTools() { return tools.isEmpty() || tools.contains("*"); }
    public Set<String> immutableTools() { return Collections.unmodifiableSet(tools); }

    public AgentDefinition copy() {
        AgentDefinition d = new AgentDefinition();
        d.name=name; d.description=description; d.prompt=prompt; d.model=model; d.effort=effort;
        d.permissionMode=permissionMode; d.isolation=isolation; d.color=color; d.memory=memory;
        d.background=background; d.maxTurns=maxTurns; d.source=source; d.sourceFile=sourceFile;
        d.tools.addAll(tools); d.disallowedTools.addAll(disallowedTools); d.skills.addAll(skills);
        return d;
    }
}
