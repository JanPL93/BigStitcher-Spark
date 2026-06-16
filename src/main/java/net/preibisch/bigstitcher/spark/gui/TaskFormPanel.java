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

import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.filechooser.FileNameExtensionFilter;

import net.preibisch.bigstitcher.spark.gui.ArgumentModel.Arg;

/**
 * A scrollable form for a single command: one labeled row per {@code @Option},
 * pre-filled with its default, with the option description as a tooltip. Enums
 * render as dropdowns, booleans as checkboxes, everything else as text fields.
 * The {@code -x/--xml} row also gets a file browser.
 */
public class TaskFormPanel extends JPanel
{
	private static final long serialVersionUID = 1L;

	private final String commandName;
	private final ArgumentModel model;
	private final Map< Arg, JComponent > widgets = new IdentityHashMap<>();

	/** notified whenever the XML field changes, so the shared top-bar field can follow. */
	private Consumer< String > onXmlChanged = s -> {};
	private JTextField xmlField;

	public TaskFormPanel( final String commandName, final ArgumentModel model )
	{
		this.commandName = commandName;
		this.model = model;
		build();
	}

	public String commandName()
	{
		return commandName;
	}

	public void setOnXmlChanged( final Consumer< String > onXmlChanged )
	{
		this.onXmlChanged = onXmlChanged;
	}

	/** Sets the XML path field (called when the shared top-bar field changes). */
	public void setXmlPath( final String path )
	{
		if ( xmlField != null && !xmlField.getText().equals( path ) )
			xmlField.setText( path );
	}

	private void build()
	{
		setLayout( new GridBagLayout() );
		final GridBagConstraints c = new GridBagConstraints();
		c.insets = new Insets( 3, 5, 3, 5 );
		c.anchor = GridBagConstraints.WEST;

		int row = 0;
		for ( final Arg arg : model.args() )
		{
			c.gridy = row++;

			// label
			c.gridx = 0;
			c.weightx = 0;
			c.fill = GridBagConstraints.NONE;
			final JLabel label = new JLabel( arg.name + ( arg.required ? " *" : "" ) );
			if ( !arg.description.isEmpty() )
				label.setToolTipText( "<html><body style='width:350px'>" + escape( arg.description ) + "</body></html>" );
			add( label, c );

			// widget
			c.gridx = 1;
			c.weightx = 1;
			c.fill = GridBagConstraints.HORIZONTAL;
			final JComponent widget = createWidget( arg );
			if ( !arg.description.isEmpty() )
				widget.setToolTipText( label.getToolTipText() );
			widgets.put( arg, widget );
			add( widget, c );

			// optional browse button for the xml option
			if ( arg.isXml() )
			{
				c.gridx = 2;
				c.weightx = 0;
				c.fill = GridBagConstraints.NONE;
				add( createBrowseButton(), c );
			}
		}
	}

	private JComponent createWidget( final Arg arg )
	{
		switch ( arg.widget )
		{
		case ENUM:
		{
			final JComboBox< Object > combo = new JComboBox<>( arg.enumConstants );
			if ( !arg.required )
				combo.insertItemAt( null, 0 ); // allow "leave at default" for optional enums
			selectEnumDefault( combo, arg );
			return combo;
		}
		case BOOLEAN:
		{
			final JCheckBox checkBox = new JCheckBox();
			checkBox.setSelected( "true".equalsIgnoreCase( arg.defaultValue ) );
			return checkBox;
		}
		default:
		{
			final JTextField field = new JTextField( arg.defaultValue, 30 );
			field.setPreferredSize( new Dimension( 320, field.getPreferredSize().height ) );
			if ( arg.isXml() )
			{
				xmlField = field;
				field.getDocument().addDocumentListener(
						new SimpleDocumentListener( () -> onXmlChanged.accept( field.getText() ) ) );
			}
			return field;
		}
		}
	}

	private static void selectEnumDefault( final JComboBox< Object > combo, final Arg arg )
	{
		if ( arg.defaultValue == null || arg.defaultValue.isEmpty() )
		{
			combo.setSelectedItem( null );
			return;
		}
		for ( int i = 0; i < combo.getItemCount(); i++ )
		{
			final Object item = combo.getItemAt( i );
			if ( item != null && item.toString().equals( arg.defaultValue ) )
			{
				combo.setSelectedIndex( i );
				return;
			}
		}
	}

	private JButton createBrowseButton()
	{
		final JButton browse = new JButton( "Browse…" );
		browse.addActionListener( e -> {
			final JFileChooser chooser = new JFileChooser();
			chooser.setFileFilter( new FileNameExtensionFilter( "BigStitcher project XML (*.xml)", "xml" ) );
			if ( xmlField != null && !xmlField.getText().trim().isEmpty() )
			{
				final File current = new File( xmlField.getText().trim() );
				if ( current.getParentFile() != null )
					chooser.setCurrentDirectory( current.getParentFile() );
			}
			if ( chooser.showOpenDialog( this ) == JFileChooser.APPROVE_OPTION )
			{
				final String path = chooser.getSelectedFile().getAbsolutePath();
				if ( xmlField != null )
					xmlField.setText( path );
				onXmlChanged.accept( path );
			}
		} );
		return browse;
	}

	/**
	 * Builds the picocli argv from the current form state.
	 *
	 * @throws IllegalArgumentException if a required field is empty; the message
	 *             lists all missing fields.
	 */
	public List< String > buildArgs()
	{
		final List< String > args = new ArrayList<>();
		final List< String > missing = new ArrayList<>();

		for ( final Arg arg : model.args() )
		{
			final JComponent widget = widgets.get( arg );

			switch ( arg.widget )
			{
			case BOOLEAN:
			{
				final boolean selected = ( ( JCheckBox ) widget ).isSelected();
				final boolean def = "true".equalsIgnoreCase( arg.defaultValue );
				if ( selected != def && selected )
					args.add( arg.name );
				break;
			}
			case ENUM:
			{
				final Object value = ( ( JComboBox< ? > ) widget ).getSelectedItem();
				if ( value == null )
				{
					if ( arg.required )
						missing.add( arg.name );
					break;
				}
				final String text = value.toString();
				if ( arg.required || !text.equals( arg.defaultValue ) )
				{
					args.add( arg.name );
					args.add( text );
				}
				break;
			}
			default:
			{
				final String text = ( ( JTextField ) widget ).getText().trim();
				if ( text.isEmpty() )
				{
					if ( arg.required )
						missing.add( arg.name );
					break;
				}
				if ( !arg.required && text.equals( arg.defaultValue ) )
					break;
				if ( arg.multiValue )
				{
					for ( final String part : text.split( "[,\\s]+" ) )
						if ( !part.isEmpty() )
						{
							args.add( arg.name );
							args.add( part );
						}
				}
				else
				{
					args.add( arg.name );
					args.add( text );
				}
				break;
			}
			}
		}

		if ( !missing.isEmpty() )
			throw new IllegalArgumentException( "Please fill in required field(s): " + String.join( ", ", missing ) );

		return args;
	}

	private static String escape( final String s )
	{
		return s.replace( "&", "&amp;" ).replace( "<", "&lt;" ).replace( ">", "&gt;" );
	}
}
