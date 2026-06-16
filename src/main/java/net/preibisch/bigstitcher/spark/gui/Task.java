/*-
 * #%L
 * Spark-based parallel BigStitcher project.
 * %%
 * Copyright (C) 2021 - 2024 Developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.bigstitcher.spark.gui;

import java.util.Collections;
import java.util.List;

/**
 * A single queued processing task: a command name, the class to invoke, the
 * picocli argv to pass it, plus mutable run-time state (status + captured log).
 */
public class Task
{
	public enum Status { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

	private final String commandName;
	private final Class< ? > commandClass;
	private final List< String > args;

	private volatile Status status = Status.QUEUED;
	private final StringBuilder log = new StringBuilder();

	public Task( final String commandName, final Class< ? > commandClass, final List< String > args )
	{
		this.commandName = commandName;
		this.commandClass = commandClass;
		this.args = Collections.unmodifiableList( args );
	}

	public String commandName()
	{
		return commandName;
	}

	public Class< ? > commandClass()
	{
		return commandClass;
	}

	public List< String > args()
	{
		return args;
	}

	public Status status()
	{
		return status;
	}

	public void setStatus( final Status status )
	{
		this.status = status;
	}

	public synchronized void appendLog( final String text )
	{
		log.append( text );
	}

	public synchronized String log()
	{
		return log.toString();
	}

	/** A short, human-readable one-line summary, e.g. {@code resave --dryRun ...}. */
	public String summary()
	{
		return commandName + " " + String.join( " ", args );
	}

	@Override
	public String toString()
	{
		return summary();
	}
}
