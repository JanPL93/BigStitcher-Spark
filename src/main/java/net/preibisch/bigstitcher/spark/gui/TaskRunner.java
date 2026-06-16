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

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs a queue of {@link Task}s sequentially, each in its own JVM subprocess,
 * mirroring the {@code java -Xmx<mem>g -Dspark.master=local[<n>] -cp <cp> <Class> <args>}
 * invocation used by the {@code ./install} generated scripts.
 *
 * All callbacks fire on the runner's background thread; callers that touch Swing
 * must marshal to the EDT themselves.
 */
public class TaskRunner
{
	/** Callbacks for queue progress. Invoked on the runner thread. */
	public interface Listener
	{
		void onTaskStarted( Task task );

		void onLogLine( Task task, String line );

		void onTaskFinished( Task task );

		void onQueueFinished();
	}

	private final int memoryGb;
	private final int threads;
	private final boolean continueOnError;
	private final Listener listener;

	private volatile Process currentProcess;
	private volatile boolean stopped;
	private Thread thread;

	public TaskRunner( final int memoryGb, final int threads, final boolean continueOnError, final Listener listener )
	{
		this.memoryGb = memoryGb;
		this.threads = threads;
		this.continueOnError = continueOnError;
		this.listener = listener;
	}

	/** Starts running the given tasks on a background thread. Returns immediately. */
	public void start( final List< Task > tasks )
	{
		final List< Task > snapshot = new ArrayList<>( tasks );
		stopped = false;
		thread = new Thread( () -> runAll( snapshot ), "bigstitcher-task-runner" );
		thread.setDaemon( true );
		thread.start();
	}

	/** Requests the queue to stop and kills the currently running subprocess. */
	public void stop()
	{
		stopped = true;
		final Process p = currentProcess;
		if ( p != null )
			p.destroy();
	}

	private void runAll( final List< Task > tasks )
	{
		try
		{
			for ( final Task task : tasks )
			{
				if ( stopped )
				{
					task.setStatus( Task.Status.CANCELLED );
					listener.onTaskFinished( task );
					continue;
				}

				final boolean ok = runOne( task );

				if ( !ok && !continueOnError )
				{
					// cancel the rest of the queue
					stopped = true;
				}
			}
		}
		finally
		{
			listener.onQueueFinished();
		}
	}

	private boolean runOne( final Task task )
	{
		task.setStatus( Task.Status.RUNNING );
		listener.onTaskStarted( task );

		final List< String > command = buildCommand( task );
		appendAndNotify( task, "$ " + String.join( " ", command ) + System.lineSeparator() );

		try
		{
			final ProcessBuilder pb = new ProcessBuilder( command );
			pb.redirectErrorStream( true );
			final Process process = pb.start();
			currentProcess = process;

			try ( BufferedReader reader = new BufferedReader(
					new InputStreamReader( process.getInputStream(), StandardCharsets.UTF_8 ) ) )
			{
				String line;
				while ( ( line = reader.readLine() ) != null )
					appendAndNotify( task, line + System.lineSeparator() );
			}

			final int exit = process.waitFor();
			currentProcess = null;

			if ( stopped && exit != 0 )
			{
				task.setStatus( Task.Status.CANCELLED );
				appendAndNotify( task, "[cancelled]" + System.lineSeparator() );
				listener.onTaskFinished( task );
				return false;
			}

			final boolean ok = exit == 0;
			task.setStatus( ok ? Task.Status.DONE : Task.Status.FAILED );
			appendAndNotify( task, "[exit code " + exit + "]" + System.lineSeparator() );
			listener.onTaskFinished( task );
			return ok;
		}
		catch ( final Exception e )
		{
			currentProcess = null;
			task.setStatus( Task.Status.FAILED );
			appendAndNotify( task, "[error] " + e + System.lineSeparator() );
			listener.onTaskFinished( task );
			return false;
		}
	}

	private void appendAndNotify( final Task task, final String text )
	{
		task.appendLog( text );
		listener.onLogLine( task, text );
	}

	private List< String > buildCommand( final Task task )
	{
		final List< String > command = new ArrayList<>();
		command.add( javaExecutable() );
		command.add( "-Xmx" + memoryGb + "g" );
		command.add( "-Dspark.master=local[" + threads + "]" );
		command.add( "-cp" );
		command.add( System.getProperty( "java.class.path" ) );
		command.add( task.commandClass().getName() );
		command.addAll( task.args() );
		return command;
	}

	private static String javaExecutable()
	{
		final String javaHome = System.getProperty( "java.home" );
		final String exe = System.getProperty( "os.name" ).toLowerCase().contains( "win" ) ? "java.exe" : "java";
		final File candidate = new File( new File( javaHome, "bin" ), exe );
		return candidate.exists() ? candidate.getAbsolutePath() : "java";
	}
}
