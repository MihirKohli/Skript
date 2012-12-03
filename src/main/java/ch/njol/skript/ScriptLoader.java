/*
 *   This file is part of Skript.
 *
 *  Skript is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Skript is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Skript.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * 
 * Copyright 2011, 2012 Peter Güttinger
 * 
 */

package ch.njol.skript;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.Listener;

import ch.njol.skript.classes.ClassInfo;
import ch.njol.skript.command.CommandEvent;
import ch.njol.skript.command.Commands;
import ch.njol.skript.command.ScriptCommand;
import ch.njol.skript.config.Config;
import ch.njol.skript.config.EntryNode;
import ch.njol.skript.config.Node;
import ch.njol.skript.config.SectionNode;
import ch.njol.skript.config.SimpleNode;
import ch.njol.skript.effects.Delay;
import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Conditional;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.Loop;
import ch.njol.skript.lang.ParseContext;
import ch.njol.skript.lang.SelfRegisteringSkriptEvent;
import ch.njol.skript.lang.SkriptEvent;
import ch.njol.skript.lang.SkriptEvent.SkriptEventInfo;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.Statement;
import ch.njol.skript.lang.Trigger;
import ch.njol.skript.lang.TriggerItem;
import ch.njol.skript.lang.TriggerSection;
import ch.njol.skript.lang.UnparsedLiteral;
import ch.njol.skript.lang.Variable;
import ch.njol.skript.lang.While;
import ch.njol.skript.localization.Language;
import ch.njol.skript.log.SimpleLog;
import ch.njol.skript.log.SkriptLogger;
import ch.njol.skript.registrations.Classes;
import ch.njol.skript.registrations.Converters;
import ch.njol.skript.util.Date;
import ch.njol.skript.util.ItemType;
import ch.njol.util.Callback;
import ch.njol.util.Pair;
import ch.njol.util.StringUtils;
import ch.njol.util.Kleenean;

/**
 * @author Peter Güttinger
 */
final public class ScriptLoader {
	private ScriptLoader() {}
	
	public static Config currentScript = null;
	
	public static SkriptEvent currentEvent = null;
	public static Class<? extends Event>[] currentEvents = null;
	
	public static List<TriggerSection> currentSections = new ArrayList<TriggerSection>();
	public static List<Loop> currentLoops = new ArrayList<Loop>();
	public static final Map<String, ItemType> currentAliases = new HashMap<String, ItemType>();
	public static final HashMap<String, String> currentOptions = new HashMap<String, String>();
	
	/**
	 * must be synchronized
	 */
	private static volatile ScriptInfo loadedScripts = new ScriptInfo();
	
	public static Kleenean hasDelayBefore = Kleenean.FALSE;
	
	public static class ScriptInfo {
		public int files, triggers, commands;
		
		public ScriptInfo() {}
		
		public ScriptInfo(final int numFiles, final int numTriggers, final int numCommands) {
			files = numFiles;
			triggers = numTriggers;
			commands = numCommands;
		}
		
		public void add(final ScriptInfo other) {
			files += other.files;
			triggers += other.triggers;
			commands += other.commands;
		}
		
		public void subtract(final ScriptInfo other) {
			files -= other.files;
			triggers -= other.triggers;
			commands -= other.commands;
		}
	}
	
	private final static class SerializedScript implements Serializable {
		static final long serialVersionUID = -6209530262798192214L;
		
		public final List<Trigger> triggers = new ArrayList<Trigger>();
		public final List<ScriptCommand> commands = new ArrayList<ScriptCommand>();
		
	}
	
	private static String indentation = "";
	
	final static List<Trigger> selfRegisteredTriggers = new ArrayList<Trigger>();
	
	/**
	 * As it's difficult to unregister events with Bukkit this set is used to prevent that any event will ever be registered more than once when reloading.
	 */
	private final static Set<Class<? extends Event>> registeredEvents = new HashSet<Class<? extends Event>>();
	
	static ScriptInfo loadScripts() {
		final File scriptsFolder = new File(Skript.getInstance().getDataFolder(), Skript.SCRIPTSFOLDER + File.separatorChar);
		if (!scriptsFolder.isDirectory())
			scriptsFolder.mkdirs();
		
		final int startErrors = SkriptLogger.getNumErrors();
		final Date start = new Date();
		
		Language.setUseLocal(false);
		final ScriptInfo i;
		try {
			i = loadScripts(scriptsFolder);
		} finally {
			Language.setUseLocal(true);
		}
		
		synchronized (loadedScripts) {
			loadedScripts.add(i);
		}
		
		if (startErrors == SkriptLogger.getNumErrors())
			Skript.info("All scripts loaded without errors!");
		
		if (i.files == 0)
			Skript.warning("No scripts were found, maybe you should write some ;)");
		if (Skript.logNormal() && i.files > 0)
			Skript.info("loaded " + i.files + " script" + (i.files == 1 ? "" : "s")
					+ " with a total of " + i.triggers + " trigger" + (i.triggers == 1 ? "" : "s")
					+ " and " + i.commands + " command" + (i.commands == 1 ? "" : "s")
					+ " in " + start.difference(new Date()));
		
		registerBukkitEvents();
		
		return i;
	}
	
	private final static ScriptInfo loadScripts(final File directory) {
		final ScriptInfo i = new ScriptInfo();
		for (final File f : directory.listFiles(new FileFilter() {
			@Override
			public boolean accept(final File f) {
				return (f.isDirectory() || f.getName().endsWith(".sk")) && !f.getName().startsWith("-");
			}
		})) {
			if (f.isDirectory()) {
				i.add(loadScripts(f));
			} else {
				i.add(loadScript(f));
			}
		}
		return i;
	}
	
	public final static ScriptInfo loadScripts(final Collection<File> files) {
		final ScriptInfo i = new ScriptInfo();
		Language.setUseLocal(false);
		try {
			for (final File f : files) {
				i.add(loadScript(f));
			}
		} finally {
			Language.setUseLocal(true);
		}
		
		synchronized (loadedScripts) {
			loadedScripts.add(i);
		}
		
		registerBukkitEvents();
		
		return i;
	}
	
	@SuppressWarnings("unchecked")
	private final static ScriptInfo loadScript(final File f) {
		File cache = null;
		if (SkriptConfig.enableScriptCaching) {
			cache = new File(f.getParentFile(), "cache" + File.separator + f.getName() + "c");
			if (cache.exists()) {
				final SimpleLog log = SkriptLogger.startSubLog();
				ObjectInputStream in = null;
				try {
					in = new ObjectInputStream(new FileInputStream(cache));
					final long lastModified = in.readLong();
					if (lastModified == f.lastModified()) {
						final SerializedScript script = (SerializedScript) in.readObject();
						triggersLoop: for (final Trigger t : script.triggers) {
							if (t.getEvent() instanceof SelfRegisteringSkriptEvent) {
								((SelfRegisteringSkriptEvent) t.getEvent()).register(t);
								selfRegisteredTriggers.add(t);
							} else {
								for (final SkriptEventInfo<?> e : Skript.getEvents()) {
									if (e.c == t.getEvent().getClass()) {
										SkriptEventHandler.addTrigger(e.events, t);
										continue triggersLoop;
									}
								}
								throw new EmptyStackException();
							}
						}
						for (final ScriptCommand c : script.commands) {
							Commands.registerCommand(c);
						}
						log.printLog();
						return new ScriptInfo(1, script.triggers.size(), script.commands.size());
					} else {
						cache.delete();
					}
				} catch (final Exception e) {
					if (Skript.debug()) {
						System.err.println("[debug] Error loading cached script '" + f.getName() + "':");
						e.printStackTrace();
					}
					unloadScript(f);
					if (in != null) {
						try {
							in.close();
						} catch (final IOException e1) {}
					}
					cache.delete();
				} finally {
					log.stop();
					if (in != null) {
						try {
							in.close();
						} catch (final IOException e) {}
					}
				}
			}
		}
		try {
			final Config config = new Config(f, true, false, ":");
			if (SkriptConfig.keepConfigsLoaded)
				SkriptConfig.configs.add(config);
			int numTriggers = 0;
			int numCommands = 0;
			
			final int numErrors = SkriptLogger.getNumErrors();
			
			currentAliases.clear();
			currentOptions.clear();
			
			currentScript = config;
			
			final SerializedScript script = new SerializedScript();
			
			for (final Node cnode : config.getMainNode()) {
				if (!(cnode instanceof SectionNode)) {
					Skript.error("invalid line - all code has to be put into triggers");
					continue;
				}
				
				final SectionNode node = ((SectionNode) cnode);
				String event = node.getName();
				
				if (event.equalsIgnoreCase("aliases")) {
					node.convertToEntries(0, "=");
					for (final Node n : node) {
						if (!(n instanceof EntryNode)) {
							Skript.error("invalid line in alias section");
							continue;
						}
						final ItemType t = Aliases.parseAlias(((EntryNode) n).getValue());
						if (t == null)
							continue;
						currentAliases.put(((EntryNode) n).getKey().toLowerCase(), t);
					}
					continue;
				} else if (event.equalsIgnoreCase("options")) {
					node.convertToEntries(0);
					for (final Node n : node) {
						if (!(n instanceof EntryNode)) {
							Skript.error("invalid line in options");
							continue;
						}
						currentOptions.put(((EntryNode) n).getKey(), ((EntryNode) n).getValue());
					}
					continue;
				} else if (event.equalsIgnoreCase("variables")) {
					node.convertToEntries(0, "=");
					for (final Node n : node) {
						if (!(n instanceof EntryNode)) {
							Skript.error("invalid line in variables");
							continue;
						}
						String name = ((EntryNode) n).getKey();
						if (name.startsWith("{") && name.endsWith("}"))
							name = name.substring(1, name.length() - 1);
						final String var = name;
						name = StringUtils.replaceAll(name, "%(.+)?%", new Callback<String, Matcher>() {
							@Override
							public String run(final Matcher m) {
								if (m.group(1).contains("{") || m.group(1).contains("}") || m.group(1).contains("%")) {
									Skript.error("'" + var + "' is not a valid name for a default variable");
									return null;
								}
								final ClassInfo<?> ci = Classes.getClassInfoFromUserInput(m.group(1));
								if (ci == null) {
									Skript.error("Can't understand the type '" + m.group(1) + "'");
									return null;
								}
								return "<" + ci.getCodeName() + ">";
							}
						});
						if (name == null || name.contains("%")) {
							continue;
						}
						if (Skript.getVariables().getVariable(Variable.splitVariableName(name)) != null)
							continue;
						final SimpleLog log = SkriptLogger.startSubLog();
						Object o = Classes.parseSimple(((EntryNode) n).getValue(), Object.class, ParseContext.CONFIG);
						log.stop();
						if (o == null) {
							log.printErrors("Can't understand the value '" + ((EntryNode) n).getValue() + "'");
							continue;
						}
						final ClassInfo<?> ci = Classes.getSuperClassInfo(o.getClass());
						if (ci.getSerializeAs() != null) {
							final ClassInfo<?> as = Classes.getSuperClassInfo(ci.getSerializeAs());
							if (as == null) {
								Skript.error("Missing class info for " + ci.getSerializeAs().getName() + ", the class to serialize " + ci.getC().getName() + " as");
								continue;
							}
							o = Converters.convert(o, as.getC());
							if (o == null) {
								Skript.error("Can't save '" + ((EntryNode) n).getValue() + "' in a variable");
								continue;
							}
						}
						Skript.getVariables().setVariable(Variable.splitVariableName(name), o);
					}
					continue;
				}
				
				if (StringUtils.count(event, '"') % 2 != 0) {
					Skript.error(Skript.quotesError);
					continue;
				}
				
				if (event.toLowerCase().startsWith("command ")) {
					currentEvent = null;
					currentEvents = Skript.array(CommandEvent.class);
					final ScriptCommand c = Commands.loadCommand(node);
					if (c != null) {
						numCommands++;
						script.commands.add(c);
					}
					continue;
				}
				if (Skript.logVeryHigh() && !Skript.debug())
					Skript.info("loading trigger '" + event + "'");
				
				if (StringUtils.startsWithIgnoreCase(event, "on "))
					event = event.substring("on ".length());
				event = replaceOptions(event);
				if (event == null)
					continue;
				final Pair<SkriptEventInfo<?>, SkriptEvent> parsedEvent = SkriptParser.parseEvent(event, "can't understand this event: '" + node.getName() + "'");
				if (parsedEvent == null) {
					continue;
				}
				
				if (Skript.debug())
					Skript.info(event + " (" + parsedEvent.second.toString(null, true) + "):");
				
				currentEvent = parsedEvent.second;
				currentEvents = parsedEvent.first.events;
				hasDelayBefore = Kleenean.FALSE;
				
				final Trigger trigger = new Trigger(config.getFile(), event, parsedEvent.second, loadItems(node));
				
				currentEvent = null;
				currentEvents = null;
				hasDelayBefore = Kleenean.FALSE;
				
				if (parsedEvent.second instanceof SelfRegisteringSkriptEvent) {
					((SelfRegisteringSkriptEvent) parsedEvent.second).register(trigger);
					selfRegisteredTriggers.add(trigger);
				} else {
					SkriptEventHandler.addTrigger(parsedEvent.first.events, trigger);
				}
				
				script.triggers.add(trigger);
				
				numTriggers++;
			}
			
			if (Skript.logHigh())
				Skript.info("loaded " + numTriggers + " trigger" + (numTriggers == 1 ? "" : "s") + " and " + numCommands + " command" + (numCommands == 1 ? "" : "s") + " from '" + config.getFileName() + "'");
			
			currentScript = null;
			
			if (SkriptConfig.enableScriptCaching) {
				if (numErrors == SkriptLogger.getNumErrors()) {
					ObjectOutputStream out = null;
					try {
						cache.getParentFile().mkdirs();
						out = new ObjectOutputStream(new FileOutputStream(cache));
						out.writeLong(f.lastModified());
						out.writeObject(script);
					} catch (final NotSerializableException e) {
						Skript.exception(e, "Cannot cache " + f.getName());
						if (out != null)
							out.close();
						cache.delete();
					} catch (final IOException e) {
						Skript.warning("Cannot cache " + f.getName() + ": " + e.getLocalizedMessage());
						if (out != null)
							out.close();
						cache.delete();
					} finally {
						if (out != null)
							out.close();
					}
				}
			}
			
			return new ScriptInfo(1, numTriggers, numCommands);
		} catch (final IOException e) {
			Skript.error("Could not load " + f.getName() + ": " + e.getLocalizedMessage());
		} catch (final Exception e) {
			Skript.exception(e, "Could not load " + f.getName());
		} finally {
			SkriptLogger.setNode(null);
		}
		return new ScriptInfo();
	}
	
	private final static void registerBukkitEvents() {
		for (final Class<? extends Event> e : SkriptEventHandler.triggers.keySet()) {
			if (!registeredEvents.contains(e)) {
				Bukkit.getPluginManager().registerEvent(e, new Listener() {}, Skript.defaultEventPriority, SkriptEventHandler.ee, Skript.getInstance());
				registeredEvents.add(e);
			}
		}
	}
	
	final static ScriptInfo unloadScript(final File script) {
		final ScriptInfo info = new ScriptInfo();
		final Iterator<List<Trigger>> triggersIter = SkriptEventHandler.triggers.values().iterator();
		while (triggersIter.hasNext()) {
			final List<Trigger> ts = triggersIter.next();
			for (int i = 0; i < ts.size(); i++) {
				if (ts.get(i).getScript().equals(script)) {
					info.triggers++;
					ts.remove(i);
					i--;
					if (ts.isEmpty())
						triggersIter.remove();
				}
			}
		}
		for (int i = 0; i < ScriptLoader.selfRegisteredTriggers.size(); i++) {
			final Trigger t = ScriptLoader.selfRegisteredTriggers.get(i);
			if (t.getScript().equals(script)) {
				info.triggers++;
				((SelfRegisteringSkriptEvent) t.getEvent()).unregister(t);
				ScriptLoader.selfRegisteredTriggers.remove(i);
				i--;
			}
		}
		info.commands = Commands.unregisterCommands(script);
		
		synchronized (loadedScripts) {
			loadedScripts.subtract(info);
		}
		
		return info;
	}
	
	private final static String replaceOptions(final String s) {
		return StringUtils.replaceAll(s, "\\{@(.+?)\\}", new Callback<String, Matcher>() {
			@Override
			public String run(final Matcher m) {
				final String option = currentOptions.get(m.group(1));
				if (option == null) {
					Skript.error("undefined option " + m.group());
					return null;
				}
				return option;
			}
		});
	}
	
	public static ArrayList<TriggerItem> loadItems(final SectionNode node) {
		
		if (Skript.debug())
			indentation += "    ";
		
		final ArrayList<TriggerItem> items = new ArrayList<TriggerItem>();
		
		Kleenean hadDelayBeforeLastIf = Kleenean.FALSE;
		
		for (final Node n : node) {
			SkriptLogger.setNode(n);
			if (n instanceof SimpleNode) {
				final SimpleNode e = (SimpleNode) n;
				final String ex = replaceOptions(e.getName());
				if (ex == null)
					continue;
				final Statement stmt = Statement.parse(ex, "can't understand this condition/effect: " + ex);
				if (stmt == null)
					continue;
				if (Skript.debug())
					Skript.info(indentation + stmt.toString(null, true));
				items.add(stmt);
				if (stmt instanceof Delay)
					hasDelayBefore = Kleenean.TRUE;
			} else if (n instanceof SectionNode) {
				if (StringUtils.startsWithIgnoreCase(n.getName(), "loop ")) {
					final String l = replaceOptions(n.getName().substring("loop ".length()));
					if (l == null)
						continue;
					@SuppressWarnings("unchecked")
					final Expression<?> loopedExpr = SkriptParser.parseExpression(l, false, ParseContext.DEFAULT, Object.class).getConvertedExpression(Object.class);
					if (loopedExpr == null)
						continue;
					if (loopedExpr.isSingle()) {
						Skript.error("Can't loop " + loopedExpr);
						continue;
					}
					if (Skript.debug())
						Skript.info(indentation + "loop " + loopedExpr.toString(null, true) + ":");
					final Kleenean hadDelayBefore = hasDelayBefore;
					items.add(new Loop(loopedExpr, (SectionNode) n));
					if (hadDelayBefore != Kleenean.TRUE && hasDelayBefore != Kleenean.FALSE)
						hasDelayBefore = Kleenean.UNKNOWN;
				} else if (StringUtils.startsWithIgnoreCase(n.getName(), "while ")) {
					final String l = replaceOptions(n.getName().substring("while ".length()));
					if (l == null)
						continue;
					final Condition c = Condition.parse(l, "Can't understand this condition: " + l);
					if (c == null)
						continue;
					if (Skript.debug())
						Skript.info(indentation + "while " + c.toString(null, true) + ":");
 					final Kleenean hadDelayBefore = hasDelayBefore;
					items.add(new While(c, (SectionNode) n));
					if (hadDelayBefore != Kleenean.TRUE && hasDelayBefore != Kleenean.FALSE)
						hasDelayBefore = Kleenean.UNKNOWN;
				} else if (n.getName().equalsIgnoreCase("else")) {
					if (items.size() == 0 || !(items.get(items.size() - 1) instanceof Conditional)) {
						Skript.error("'else' has to be placed just after the end of a conditional section");
						continue;
					}
					if (Skript.debug())
						Skript.info(indentation + "else:");
					final Kleenean hadDelayAfterLastIf = hasDelayBefore;
					hasDelayBefore = hadDelayBeforeLastIf;
					((Conditional) items.get(items.size() - 1)).loadElseClause((SectionNode) n);
					hasDelayBefore = hasDelayBefore.and(hadDelayAfterLastIf).or(hadDelayBeforeLastIf);
				} else {
					String name = n.getName();
					if (StringUtils.startsWithIgnoreCase(name, "if "))
						name = name.substring(3);
					final Condition cond = Condition.parse(name, "can't understand this condition: '" + name + "'");
					if (cond == null)
						continue;
					if (Skript.debug())
						Skript.info(indentation + cond.toString(null, true) + ":");
					final Kleenean hadDelayBefore = hasDelayBefore;
					hadDelayBeforeLastIf = hadDelayBefore;
					items.add(new Conditional(cond, (SectionNode) n));
					if (hadDelayBefore != Kleenean.TRUE && hasDelayBefore != Kleenean.FALSE)
						hasDelayBefore = Kleenean.UNKNOWN;
				}
			}
		}
		
		for (int i = 0; i < items.size() - 1; i++)
			items.get(i).setNext(items.get(i + 1));
		
		SkriptLogger.setNode(node);
		
		if (Skript.debug())
			indentation = indentation.substring(0, indentation.length() - 4);
		
		return items;
	}
	
	/**
	 * For unit testing
	 * 
	 * @param node
	 * @return
	 */
	static Trigger loadTrigger(final SectionNode node) {
		String event = node.getName();
		if (event.toLowerCase().startsWith("on "))
			event = event.substring("on ".length());
		
		final Pair<SkriptEventInfo<?>, SkriptEvent> parsedEvent = SkriptParser.parseEvent(event, "can't understand this event: '" + node.getName() + "'");
		
		currentEvent = parsedEvent.second;
		currentEvents = parsedEvent.first.events;
		
		final Trigger t = new Trigger(null, event, parsedEvent.second, loadItems(node));
		
		currentEvent = null;
		currentEvents = null;
		
		return t;
	}
	
	public final static int loadedScripts() {
		synchronized (loadedScripts) {
			return loadedScripts.files;
		}
	}
	
	public final static int loadedCommands() {
		synchronized (loadedScripts) {
			return loadedScripts.commands;
		}
	}
	
	public final static int loadedTriggers() {
		synchronized (loadedScripts) {
			return loadedScripts.triggers;
		}
	}
}
