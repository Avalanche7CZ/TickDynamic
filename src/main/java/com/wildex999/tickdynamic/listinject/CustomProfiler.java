package com.wildex999.tickdynamic.listinject;

import com.wildex999.tickdynamic.TickDynamicMod;
import net.minecraft.profiler.Profiler;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/*
 * This profiler will note where the world is while ticking Entities
 * when using a for loop for iterating.
 * This will allow us to be 100% accurate while the world is iterating, as we would know
 * whether we are ticking, or looping the list from inside a ticking entity.
 * 
 * This does however add a bit of an overhead with the function calls and String compare.
 */


public class CustomProfiler extends Profiler {

	public enum Stage {
		None, //Just pass on to original
		BeforeLoop, //World Tick has started
		InLoop, //We have started looping entities
		InTick, //We are currently ticking a single entity
		InRemove //Removing dead Entity
	}
	
	public final Profiler original;
	public Stage stage;
	public boolean reachedTile; //Set to true when starting to tick TileEntities
	
	
	private int depthCount; //start and end can be called inside Entity tick, we have to track it
	
	public CustomProfiler(Profiler originalProfiler) {
		this.original = originalProfiler;
		this.stage = Stage.None;
		this.reachedTile = false;
	}

	//Set new Stage, with the ability to log the change for debugging
	public void setStage(Stage stage) {
		if(this.stage != stage && TickDynamicMod.debugProfiler) {
			System.out.println("[TickDynamic][Profiler] Stage change: " + this.stage + " -> " + stage);
		}
		this.stage = stage;
	}
	
	private static final Set<String> LOOP_START_SECTIONS = parseSystemSet("tickdynamic.profiler.loopStart", "regular,entities");
	private static final Set<String> ENTITY_TICK_SECTIONS = parseSystemSet("tickdynamic.profiler.entityTick", "tick,entityTick");
	private static final Set<String> REMOVE_SECTIONS = parseSystemSet("tickdynamic.profiler.remove", "remove");
	private static final Set<String> TILE_START_SECTIONS = parseSystemSet("tickdynamic.profiler.tileStart", "blockEntities,tileEntities");
	private static final boolean HEURISTICS_ENABLED = Boolean.parseBoolean(System.getProperty("tickdynamic.profiler.enableHeuristics", "true"));

	private static Set<String> parseSystemSet(String key, String defaults) {
		String raw = System.getProperty(key, defaults);
		Set<String> set = new HashSet<String>();
		for(String part : raw.split(",")) {
			String s = part.trim();
			if(!s.isEmpty()) set.add(s);
		}
		return set;
	}

	@Override
    public void startSection(String sectionName)
    {
		if(TickDynamicMod.debugProfiler) {
			//Lightweight trace when enabled
			System.out.println("[TickDynamic][Profiler] startSection: " + sectionName + " (stage=" + stage + ")");
		}

		String lower = sectionName.toLowerCase(Locale.ROOT);
		switch(stage) {
		case None:
			break;
		case BeforeLoop:
			if(LOOP_START_SECTIONS.contains(lower)) {
				setStage(Stage.InLoop);
			} else if(HEURISTICS_ENABLED && lower.contains("entity") && !lower.contains("block") && !lower.contains("tile")) {
				// Heuristic for hybrid forks that add a differently named entity loop section
				setStage(Stage.InLoop);
			}
			break;
		case InLoop:
			if(ENTITY_TICK_SECTIONS.contains(lower) || (HEURISTICS_ENABLED && lower.contains("tick") && lower.contains("ent"))) {
				setStage(Stage.InTick);
				depthCount = 0;
			} else if(REMOVE_SECTIONS.contains(lower)) {
				setStage(Stage.InRemove);
				depthCount = 0;
			} else if(TILE_START_SECTIONS.contains(lower) || (HEURISTICS_ENABLED && lower.contains("tile") && lower.contains("ent"))) {
				setStage(Stage.None);
				reachedTile = true;
			}
			break;
		case InTick:
			//Sometimes the InTick isn't correctly closed, allow some leeway here
			if(depthCount <= 1 && REMOVE_SECTIONS.contains(lower)) {
				setStage(Stage.InRemove);
				depthCount = 0;
				break;
			}
		case InRemove:
			depthCount++;
			break;
		}

		original.startSection(sectionName);
    }
	
	@Override
	public void endSection() {
		switch(stage) {
		case InTick:
			if(depthCount-- <= 0)
				setStage(Stage.InLoop);
			break;
		case InRemove:
			if(depthCount-- <= 0)
				setStage(Stage.InLoop);
			break;
		default:
			break;
		}
		original.endSection();
	}
	
}
