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

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import picocli.CommandLine;
import picocli.CommandLine.Model.OptionSpec;

/**
 * Introspects a picocli command class and exposes its {@code @Option}s as a list
 * of {@link Arg} descriptors that a Swing form can render and turn back into an
 * argv list. Nothing here is command-specific; everything is derived by
 * reflection from the picocli {@code CommandSpec}.
 */
public class ArgumentModel
{
	/** Which kind of widget best represents an option. */
	public enum Widget { ENUM, BOOLEAN, TEXT }

	/** Descriptor for a single {@code @Option}. */
	public static class Arg
	{
		/** the primary (longest) option name, e.g. {@code --threshold} */
		public final String name;
		/** all names, e.g. {@code -t}, {@code --threshold} */
		public final String[] names;
		public final Class< ? > type;
		public final String description;
		public final boolean required;
		public final boolean multiValue;
		public final Widget widget;
		/** enum constants when {@link #widget} == ENUM, else null */
		public final Object[] enumConstants;
		/** the default value as a display string (may be empty) */
		public final String defaultValue;

		Arg( final String name, final String[] names, final Class< ? > type, final String description,
				final boolean required, final boolean multiValue, final Widget widget,
				final Object[] enumConstants, final String defaultValue )
		{
			this.name = name;
			this.names = names;
			this.type = type;
			this.description = description;
			this.required = required;
			this.multiValue = multiValue;
			this.widget = widget;
			this.enumConstants = enumConstants;
			this.defaultValue = defaultValue;
		}

		/** true if this is the input project XML option ({@code -x}/{@code --xml}). */
		public boolean isXml()
		{
			for ( final String n : names )
				if ( n.equals( "-x" ) || n.equals( "--xml" ) )
					return true;
			return false;
		}
	}

	private final Class< ? > commandClass;
	private final List< Arg > args;

	public ArgumentModel( final Class< ? > commandClass )
	{
		this.commandClass = commandClass;
		this.args = introspect( commandClass );
	}

	public Class< ? > commandClass()
	{
		return commandClass;
	}

	public List< Arg > args()
	{
		return args;
	}

	private static List< Arg > introspect( final Class< ? > commandClass )
	{
		final Object instance;
		try
		{
			instance = commandClass.getConstructor().newInstance();
		}
		catch ( final Exception e )
		{
			throw new IllegalStateException( "Cannot instantiate command " + commandClass.getName()
					+ " (needs a public no-arg constructor): " + e, e );
		}

		final CommandLine cmd = new CommandLine( instance );
		final List< Arg > result = new ArrayList<>();

		for ( final OptionSpec option : cmd.getCommandSpec().options() )
		{
			// skip auto-generated help/version options
			if ( option.usageHelp() || option.versionHelp() )
				continue;

			final Class< ? > type = option.type();
			final boolean multiValue = option.isMultiValue();
			// for multi-value options, type() is the array/collection type; the
			// component type tells us what each element looks like
			final Class< ? > elementType = multiValue ? option.auxiliaryTypes()[ 0 ] : type;

			final Widget widget;
			final Object[] enumConstants;
			if ( elementType.isEnum() )
			{
				widget = Widget.ENUM;
				enumConstants = elementType.getEnumConstants();
			}
			else if ( elementType == boolean.class || elementType == Boolean.class )
			{
				widget = Widget.BOOLEAN;
				enumConstants = null;
			}
			else
			{
				widget = Widget.TEXT;
				enumConstants = null;
			}

			result.add( new Arg(
					option.longestName(),
					option.names(),
					elementType,
					joinDescription( option.description() ),
					option.required(),
					multiValue,
					widget,
					enumConstants,
					defaultDisplay( option ) ) );
		}

		return result;
	}

	private static String joinDescription( final String[] description )
	{
		if ( description == null || description.length == 0 )
			return "";
		return String.join( " ", description );
	}

	/**
	 * Reads the option's current value off the freshly instantiated command
	 * (i.e. the field initializer) and renders it as a display string. Arrays
	 * are joined with commas; nulls become the empty string.
	 */
	private static String defaultDisplay( final OptionSpec option )
	{
		final Object value = option.getValue();
		if ( value == null )
			return "";

		if ( value.getClass().isArray() )
		{
			final int length = Array.getLength( value );
			final List< String > parts = new ArrayList<>( length );
			for ( int i = 0; i < length; i++ )
			{
				final Object element = Array.get( value, i );
				parts.add( element == null ? "" : element.toString() );
			}
			return String.join( ",", parts );
		}

		if ( value instanceof Iterable )
		{
			final List< String > parts = new ArrayList<>();
			for ( final Object element : ( Iterable< ? > ) value )
				parts.add( element == null ? "" : element.toString() );
			return String.join( ",", parts );
		}

		return value.toString();
	}

	@Override
	public String toString()
	{
		return commandClass.getSimpleName() + " " + Arrays.toString( args.toArray() );
	}
}
