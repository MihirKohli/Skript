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
 * Copyright 2011-2013 Peter Güttinger
 * 
 */

package ch.njol.skript.entity;

import org.bukkit.entity.Pig;

import ch.njol.skript.lang.Literal;
import ch.njol.skript.lang.SkriptParser.ParseResult;

/**
 * @author Peter Güttinger
 */
@SuppressWarnings("serial")
public class PigData extends EntityData<Pig> {
	
	static {
		register(PigData.class, "pig", Pig.class, "unsaddled pig", "pig", "saddled pig");
	}
	
	private int saddled;
	
	@Override
	protected boolean init(final Literal<?>[] exprs, final int matchedPattern, final ParseResult parseResult) {
		saddled = matchedPattern - 1;
		return true;
	}
	
	@Override
	public String serialize() {
		return "" + saddled;
	}
	
	@Override
	protected boolean deserialize(final String s) {
		try {
			saddled = Integer.parseInt(s);
			return Math.abs(saddled) <= 1;
		} catch (final NumberFormatException e) {
			return false;
		}
	}
	
	@Override
	public void set(final Pig entity) {
		if (saddled != 0)
			entity.setSaddle(saddled == 1);
	}
	
	@Override
	protected boolean match(final Pig entity) {
		return saddled == 0 || entity.hasSaddle() == (saddled == 1);
	}
	
	@Override
	public Class<? extends Pig> getType() {
		return Pig.class;
	}
	
	@Override
	public boolean equals(final Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (!(obj instanceof PigData))
			return false;
		final PigData other = (PigData) obj;
		return other.saddled == saddled;
	}
	
	@Override
	public int hashCode() {
		return saddled;
	}
	
	@Override
	protected boolean isSupertypeOf_i(final EntityData<? extends Pig> e) {
		if (e instanceof PigData)
			return saddled == 0 || ((PigData) e).saddled == saddled;
		return false;
	}
	
}